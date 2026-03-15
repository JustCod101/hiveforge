package com.hiveforge.controller;

import com.hiveforge.llm.StreamCallback;
import com.hiveforge.manager.HiveResult;
import com.hiveforge.manager.HiveTask;
import com.hiveforge.manager.ManagerAgent;
import com.hiveforge.repository.HiveTaskRepository;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.repository.WorkerTraceRepository;
import com.hiveforge.trace.WorkerTrace;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/hive")
public class HiveController {

    private final ManagerAgent managerAgent;
    private final HiveTaskRepository taskRepo;
    private final WorkerAgentRepository workerRepo;
    private final WorkerTraceRepository traceRepo;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public HiveController(ManagerAgent managerAgent,
                          HiveTaskRepository taskRepo,
                          WorkerAgentRepository workerRepo,
                          WorkerTraceRepository traceRepo) {
        this.managerAgent = managerAgent;
        this.taskRepo = taskRepo;
        this.workerRepo = workerRepo;
        this.traceRepo = traceRepo;
    }

    /**
     * Start a hive task with SSE real-time streaming.
     */
    @GetMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(@RequestParam String query) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes

        executor.submit(() -> {
            try {
                HiveResult result = managerAgent.executeHive(query, new StreamCallback() {
                    @Override
                    public void onEvent(String type, String message) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(type)
                                    .data(Map.of(
                                            "type", type,
                                            "message", message,
                                            "timestamp", Instant.now().toString()
                                    )));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }
                });

                emitter.send(SseEmitter.event()
                        .name("final_report")
                        .data(result.getReport()));
                emitter.complete();

            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Get hive task status.
     */
    @GetMapping("/{taskId}")
    public HiveTask getTask(@PathVariable String taskId) {
        return taskRepo.findById(taskId);
    }

    /**
     * Get all workers and their DNA snapshots for a task.
     */
    @GetMapping("/{taskId}/workers")
    public List<Map<String, Object>> getWorkers(@PathVariable String taskId) {
        return workerRepo.findByTaskId(taskId);
    }

    /**
     * Get worker execution trace.
     */
    @GetMapping("/workers/{workerId}/trace")
    public List<WorkerTrace> getWorkerTrace(@PathVariable String workerId) {
        return traceRepo.findByWorkerId(workerId);
    }

    /**
     * Get worker DNA snapshot (available even after destruction).
     */
    @GetMapping("/workers/{workerId}/dna")
    public Map<String, Object> getWorkerDna(@PathVariable String workerId) {
        Map<String, Object> worker = workerRepo.findById(workerId);
        if (worker == null) {
            return Map.of("error", "Worker not found");
        }
        return Map.of(
                "SOUL.md", worker.get("soul_md"),
                "AGENTS.md", worker.get("agents_md"),
                "TASK.md", worker.get("task_md")
        );
    }

    /**
     * Task history list.
     */
    @GetMapping("/history")
    public List<HiveTask> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskRepo.findRecent(page * size, size);
    }
}
