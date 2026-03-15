package com.hiveforge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveforge.llm.StreamCallback;
import com.hiveforge.manager.HiveResult;
import com.hiveforge.manager.HiveTask;
import com.hiveforge.manager.ManagerAgent;
import com.hiveforge.repository.HiveTaskRepository;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.repository.WorkerTraceRepository;
import com.hiveforge.trace.WorkerTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * HiveController — 蜂群任务 API 控制器
 *
 * SSE 实时推送蜂群全过程事件：
 * - hive_start / planning / plan_ready / spawning / worker_spawned
 * - executing / strategy / worker_start / worker_done
 * - aggregating / destroying / worker_destroyed
 * - final_report / hive_complete / hive_error
 *
 * 心跳机制：每 15 秒发送 heartbeat 事件，防止代理/CDN 超时断连。
 */
@RestController
@RequestMapping("/api/v1/hive")
@CrossOrigin(origins = "*")
public class HiveController {

    private static final Logger log = LoggerFactory.getLogger(HiveController.class);

    /** SSE 超时：10 分钟 */
    private static final long SSE_TIMEOUT_MS = 600_000L;

    /** 心跳间隔：15 秒 */
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final ManagerAgent managerAgent;
    private final HiveTaskRepository taskRepo;
    private final WorkerAgentRepository workerRepo;
    private final WorkerTraceRepository traceRepo;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("hive-sse-" + t.getId());
        return t;
    });

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("hive-heartbeat");
                return t;
            });

    public HiveController(ManagerAgent managerAgent,
                          HiveTaskRepository taskRepo,
                          WorkerAgentRepository workerRepo,
                          WorkerTraceRepository traceRepo,
                          ObjectMapper objectMapper) {
        this.managerAgent = managerAgent;
        this.taskRepo = taskRepo;
        this.workerRepo = workerRepo;
        this.traceRepo = traceRepo;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // 1. SSE 流式执行蜂群任务
    // ============================================================

    /**
     * GET /api/v1/hive/execute?query=...
     *
     * SSE 流式推送蜂群全过程。事件格式：
     * event: {type}
     * data: {"type":"...","message":"...","timestamp":"...","taskId":"..."}
     */
    @GetMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(@RequestParam String query) {
        if (query == null || query.isBlank()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("error", "Missing required parameter: query")));
                emitter.complete();
            } catch (IOException ignored) {}
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 跟踪连接状态，避免往已关闭的 emitter 发送
        var connectionAlive = new java.util.concurrent.atomic.AtomicBoolean(true);

        emitter.onCompletion(() -> {
            connectionAlive.set(false);
            log.debug("[SSE] Connection completed");
        });
        emitter.onTimeout(() -> {
            connectionAlive.set(false);
            log.warn("[SSE] Connection timed out");
            emitter.complete();
        });
        emitter.onError(ex -> {
            connectionAlive.set(false);
            log.warn("[SSE] Connection error: {}", ex.getMessage());
        });

        // 启动心跳 — 防止代理/CDN 超时断连
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!connectionAlive.get()) return;
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Map.of("timestamp", Instant.now().toString())));
            } catch (IOException e) {
                connectionAlive.set(false);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 异步执行蜂群任务
        executor.submit(() -> {
            String taskId = null;
            try {
                HiveResult result = managerAgent.executeHive(query, new StreamCallback() {
                    @Override
                    public void onEvent(String type, String message) {
                        if (!connectionAlive.get()) return;
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(type)
                                    .data(Map.of(
                                            "type", type,
                                            "message", message,
                                            "timestamp", Instant.now().toString()
                                    )));
                        } catch (IOException e) {
                            connectionAlive.set(false);
                            log.debug("[SSE] Failed to send event '{}': {}", type, e.getMessage());
                        }
                    }
                });

                taskId = result.getTaskId();

                // 发送最终报告（结构化数据）
                if (connectionAlive.get()) {
                    emitter.send(SseEmitter.event()
                            .name("final_report")
                            .data(Map.of(
                                    "type", "final_report",
                                    "taskId", result.getTaskId(),
                                    "report", result.getReport(),
                                    "workerCount", result.getWorkerResults().size(),
                                    "successCount", result.getWorkerResults().stream()
                                            .filter(r -> r.isSuccess()).count(),
                                    "timestamp", Instant.now().toString()
                            )));
                    emitter.complete();
                }

            } catch (Exception e) {
                log.error("[SSE] Hive execution failed", e);
                if (connectionAlive.get()) {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("hive_error")
                                .data(Map.of(
                                        "type", "hive_error",
                                        "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                                        "taskId", taskId != null ? taskId : "",
                                        "timestamp", Instant.now().toString()
                                )));
                    } catch (IOException ignored) {}
                    emitter.completeWithError(e);
                }
            } finally {
                heartbeat.cancel(false);
                connectionAlive.set(false);
            }
        });

        return emitter;
    }

    // ============================================================
    // 2. 任务状态查询
    // ============================================================

    /**
     * GET /api/v1/hive/{taskId} — 查询蜂群任务状态
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        HiveTask task = taskRepo.findById(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

    // ============================================================
    // 3. Worker 列表 + DNA 快照
    // ============================================================

    /**
     * GET /api/v1/hive/{taskId}/workers — 该任务下所有 Worker 及其 DNA 快照
     */
    @GetMapping("/{taskId}/workers")
    public ResponseEntity<?> getWorkers(@PathVariable String taskId) {
        HiveTask task = taskRepo.findById(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> workers = workerRepo.findByTaskId(taskId);
        return ResponseEntity.ok(workers);
    }

    // ============================================================
    // 4. Worker 执行轨迹
    // ============================================================

    /**
     * GET /api/v1/hive/workers/{workerId}/trace — Worker 的 ReAct 循环轨迹
     */
    @GetMapping("/workers/{workerId}/trace")
    public ResponseEntity<?> getWorkerTrace(@PathVariable String workerId) {
        Map<String, Object> worker = workerRepo.findById(workerId);
        if (worker == null) {
            return ResponseEntity.notFound().build();
        }
        List<WorkerTrace> traces = traceRepo.findByWorkerId(workerId);
        return ResponseEntity.ok(traces);
    }

    // ============================================================
    // 5. Worker DNA 查询（即使已销毁）
    // ============================================================

    /**
     * GET /api/v1/hive/workers/{workerId}/dna — Worker DNA 快照
     * DNA 在 spawning 阶段已持久化到 SQLite，目录销毁后仍可追溯。
     */
    @GetMapping("/workers/{workerId}/dna")
    public ResponseEntity<?> getWorkerDna(@PathVariable String workerId) {
        Map<String, Object> worker = workerRepo.findById(workerId);
        if (worker == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "workerId", workerId,
                "workerName", worker.getOrDefault("worker_name", ""),
                "status", worker.getOrDefault("status", ""),
                "SOUL.md", worker.getOrDefault("soul_md", ""),
                "AGENTS.md", worker.getOrDefault("agents_md", ""),
                "TASK.md", worker.getOrDefault("task_md", "")
        ));
    }

    // ============================================================
    // 6. 任务历史列表
    // ============================================================

    /**
     * GET /api/v1/hive/history?page=0&size=20 — 历史任务分页列表
     */
    @GetMapping("/history")
    public List<HiveTask> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskRepo.findRecent(page * size, size);
    }
}
