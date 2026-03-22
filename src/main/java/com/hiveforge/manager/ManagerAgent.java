package com.hiveforge.manager;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.llm.StreamCallback;
import com.hiveforge.repository.DnaTemplateRepository;
import com.hiveforge.repository.DnaTemplateRepository.DnaTemplate;
import com.hiveforge.repository.HiveTaskRepository;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.spawner.SpawnedWorker;
import com.hiveforge.spawner.WorkerSpawner;
import com.hiveforge.worker.WorkerEngine;
import com.hiveforge.worker.WorkerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manager Agent — 蜂群指挥官
 *
 * 完整生命周期：
 * 1. PLANNING   — LLM 分析用户任务 → 分解为多个 Worker 计划（JSON）
 * 2. SPAWNING   — 调用 WorkerSpawner 为每个 Worker 生成 DNA
 * 3. EXECUTING  — 按策略执行（parallel / sequential / dag）
 * 4. AGGREGATING — LLM 汇总所有 Worker 结果 → 最终报告
 * 5. DESTROYING  — rm -rf 工作目录（用后即焚）
 *
 * 全过程通过 StreamCallback 实时推送事件。
 */
@Service
public class ManagerAgent {

    private static final Logger log = LoggerFactory.getLogger(ManagerAgent.class);

    private final LlmClient llmClient;
    private final WorkerSpawner workerSpawner;
    private final WorkerEngine workerEngine;
    private final ResultAggregator resultAggregator;
    private final HiveTaskRepository taskRepo;
    private final WorkerAgentRepository workerAgentRepo;
    private final DnaTemplateRepository templateRepo;
    private final ObjectMapper objectMapper;

    @Value("${hiveforge.max-workers-per-task:10}")
    private int maxWorkersPerTask;

    @Value("${hiveforge.worker-timeout-seconds:300}")
    private int workerTimeoutSeconds;

    /** Worker 执行线程池 — 虚拟线程风格，按任务动态伸缩 */
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("hive-worker-" + t.getId());
        return t;
    });

    // ============================================================
    // Planning Prompt — 指导 LLM 分解复杂任务
    // ============================================================

    private static final String PLANNING_PROMPT = """
        你是一个任务规划专家。分析用户的复杂任务，将其分解为多个专业子任务，
        每个子任务由一个独立的 Worker Agent 执行。

        ## 可用的 Worker 类型模板
        %s

        ## 可用的工具列表
        file_read, file_write, file_list, web_search, http_call, shell_exec, calculate

        ## 用户任务
        %s

        ## 规划原则
        1. 每个 Worker 应有明确的单一职责，避免一个 Worker 做太多事
        2. 优先使用已有模板（template 字段填模板名），没有合适模板时设为 null
        3. 如果子任务之间有依赖关系，使用 depends_on 指定前置 Worker 的 name
        4. 如果子任务完全独立，execution_strategy 设为 "parallel"
        5. 如果有明确的先后顺序且无交叉，设为 "sequential"
        6. 如果有部分依赖部分独立，设为 "dag"
        7. Worker 数量不要超过 %d 个
        8. tools_needed 只填 Worker 真正需要的工具

        ## 输出格式（严格 JSON，不要有其他文本）
        ```json
        {
          "task_summary": "一句话概述任务",
          "workers": [
            {
              "name": "worker_英文名（下划线分隔）",
              "role": "角色描述（一句话）",
              "tools_needed": ["web_search", "file_write"],
              "task_description": "该 Worker 的具体任务描述",
              "depends_on": [],
              "priority": 1,
              "template": "模板名或null"
            }
          ],
          "execution_strategy": "parallel 或 sequential 或 dag",
          "aggregation_instruction": "如何将各 Worker 结果合并为最终报告"
        }
        ```
        """;

    public ManagerAgent(LlmClient llmClient,
                        WorkerSpawner workerSpawner,
                        WorkerEngine workerEngine,
                        ResultAggregator resultAggregator,
                        HiveTaskRepository taskRepo,
                        WorkerAgentRepository workerAgentRepo,
                        DnaTemplateRepository templateRepo,
                        ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.workerSpawner = workerSpawner;
        this.workerEngine = workerEngine;
        this.resultAggregator = resultAggregator;
        this.taskRepo = taskRepo;
        this.workerAgentRepo = workerAgentRepo;
        this.templateRepo = templateRepo;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // 主入口 — 蜂群任务执行
    // ============================================================

    /**
     * 执行蜂群任务的主入口。
     * 整个流程通过 StreamCallback 实时推送事件给前端。
     *
     * @param userQuery 用户的原始复杂任务描述
     * @param callback  SSE 事件回调
     * @return 蜂群执行结果（含 taskId、最终报告、各 Worker 结果）
     */
    public HiveResult executeHive(String userQuery, StreamCallback callback) {
        String taskId = UUID.randomUUID().toString();
        HiveTask task = new HiveTask(taskId, userQuery);
        taskRepo.save(task);

        List<SpawnedWorker> spawnedWorkers = new ArrayList<>();

        try {
            callback.onEvent("hive_start", "蜂群任务启动: " + taskId);
            log.info("[Manager] Hive task started: {}", taskId);

            // ===== Phase 1: 任务规划 =====
            callback.onEvent("planning", "Manager 正在分析任务并规划 Worker...");
            task.setStatus("PLANNING");
            taskRepo.update(task);

            TaskPlan plan = planTask(userQuery);
            task.setTaskPlan(toJson(plan));
            task.setWorkerCount(plan.getWorkers().size());
            taskRepo.update(task);

            callback.onEvent("plan_ready", String.format(
                    "任务已分解为 %d 个 Worker，策略: %s — %s",
                    plan.getWorkers().size(),
                    plan.getExecutionStrategy(),
                    plan.getTaskSummary()));

            log.info("[Manager] Plan ready: {} workers, strategy={}, summary={}",
                    plan.getWorkers().size(), plan.getExecutionStrategy(), plan.getTaskSummary());

            // ===== Phase 2: 孕育 Workers =====
            callback.onEvent("spawning", "正在生成 Worker DNA...");
            task.setStatus("SPAWNING");
            taskRepo.update(task);

            for (WorkerPlan wp : plan.getWorkers()) {
                SpawnedWorker worker = workerSpawner.spawn(taskId, wp, userQuery);
                spawnedWorkers.add(worker);

                callback.onEvent("worker_spawned", String.format(
                        "Worker [%s] 已孕育 — 角色: %s, 工具: %s, 目录: %s",
                        worker.getName(),
                        wp.getRole(),
                        wp.getToolsNeeded(),
                        worker.getDir()));
            }

            // ===== Phase 3: 按策略执行 =====
            callback.onEvent("executing", "Workers 开始执行任务...");
            task.setStatus("EXECUTING");
            taskRepo.update(task);

            List<WorkerResult> results = executeWorkers(
                    spawnedWorkers, plan, callback);

            // 统计执行结果
            long successCount = results.stream().filter(WorkerResult::isSuccess).count();
            long failCount = results.size() - successCount;
            callback.onEvent("execution_summary", String.format(
                    "执行完成: %d 成功, %d 失败（共 %d 个 Worker）",
                    successCount, failCount, results.size()));

            // ===== Phase 4: 结果聚合 =====
            callback.onEvent("aggregating", "Manager 正在汇总所有 Worker 结果...");
            task.setStatus("AGGREGATING");
            taskRepo.update(task);

            String finalReport = resultAggregator.aggregate(
                    userQuery, plan, results, callback);

            // ===== 持久化报告到文件 =====
            Path reportFile = saveReportToFile(taskId, userQuery, finalReport);
            callback.onEvent("report_saved", "最终报告已保存: " + reportFile);

            // ===== Phase 5: 用后即焚 =====
            callback.onEvent("destroying", "清理 Worker 目录（用后即焚）...");
            for (SpawnedWorker worker : spawnedWorkers) {
                destroyWorker(worker, callback);
            }
            // 清理任务级目录（如果所有 Worker 已销毁，父目录为空）
            destroyTaskDir(taskId);

            // ===== 完成 =====
            task.setStatus("COMPLETED");
            task.setFinalReport(finalReport);
            task.setCompletedAt(Instant.now().toString());
            taskRepo.update(task);

            callback.onEvent("hive_complete", "蜂群任务完成: " + taskId);
            log.info("[Manager] Hive task completed: {}", taskId);

            return new HiveResult(taskId, finalReport, results);

        } catch (Exception e) {
            log.error("[Manager] Hive task failed: {}", taskId, e);
            task.setStatus("FAILED");
            taskRepo.update(task);

            // 失败时也要销毁已创建的 Worker 目录
            for (SpawnedWorker worker : spawnedWorkers) {
                try {
                    destroyWorker(worker, callback);
                } catch (Exception ex) {
                    log.warn("[Manager] Failed to cleanup worker on error: {}", worker.getName());
                }
            }

            callback.onEvent("hive_error", "蜂群任务失败: " + e.getMessage());
            throw new RuntimeException("Hive execution failed", e);
        }
    }

    // ============================================================
    // Phase 1: 任务规划 — LLM 分解复杂任务
    // ============================================================

    /**
     * 调用 LLM 分析用户任务，输出结构化的 TaskPlan JSON。
     * 包含模板列表注入，让 LLM 知道有哪些现成的 Agent 人格可用。
     */
    private TaskPlan planTask(String userQuery) {
        // 获取所有可用的 DNA 模板
        List<DnaTemplate> templates = templateRepo.findAll();
        String templateDesc;
        if (templates.isEmpty()) {
            templateDesc = "（暂无预定义模板，所有 Worker 将由 LLM 从零生成 DNA）";
        } else {
            templateDesc = templates.stream()
                    .map(t -> String.format("- **%s** [%s]: %s (已使用 %d 次, 平均质量 %.1f)",
                            t.templateName(), t.category(), t.description(),
                            t.usageCount(), t.avgQuality()))
                    .collect(Collectors.joining("\n"));
        }

        String prompt = String.format(PLANNING_PROMPT, templateDesc, userQuery, maxWorkersPerTask);

        log.info("[Manager] Planning task, query length={}", userQuery.length());
        String response = llmClient.chat(prompt);
        log.debug("[Manager] Planning LLM response length={}", response.length());

        TaskPlan plan = parseTaskPlan(response);

        // 校验与修正
        validatePlan(plan);

        return plan;
    }

    /**
     * 解析 LLM 返回的 JSON 为 TaskPlan。
     * 兼容 LLM 可能在 JSON 外包裹 markdown 代码块的情况。
     */
    private TaskPlan parseTaskPlan(String rawResponse) {
        try {
            String json = extractJson(rawResponse);
            return objectMapper.readValue(json, TaskPlan.class);
        } catch (Exception e) {
            log.error("[Manager] Failed to parse task plan, response:\n{}", rawResponse, e);
            throw new RuntimeException("Failed to parse task plan from LLM response: " + e.getMessage(), e);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 内容。
     * 处理 ```json ... ``` 包裹、纯 JSON、或混合文本中的 JSON。
     */
    private String extractJson(String raw) {
        String trimmed = raw.trim();

        // Case 1: ```json ... ```
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // Case 2: ``` ... ```（不带 language tag）
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                String inner = trimmed.substring(start, end).trim();
                if (inner.startsWith("{")) {
                    return inner;
                }
            }
        }

        // Case 3: 找到第一个 { 和最后一个 }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        // Case 4: 原样返回，让 Jackson 尝试解析
        return trimmed;
    }

    /**
     * 校验并修正 TaskPlan：
     * - Worker 数量不超过上限
     * - 每个 Worker 必须有 name 和 toolsNeeded
     * - depends_on 引用的 Worker 必须存在
     * - DAG 不能有环
     */
    private void validatePlan(TaskPlan plan) {
        if (plan.getWorkers() == null || plan.getWorkers().isEmpty()) {
            throw new RuntimeException("Task plan has no workers");
        }

        // 截断过多的 Worker
        if (plan.getWorkers().size() > maxWorkersPerTask) {
            log.warn("[Manager] Plan has {} workers, truncating to {}",
                    plan.getWorkers().size(), maxWorkersPerTask);
            plan.setWorkers(plan.getWorkers().subList(0, maxWorkersPerTask));
        }

        // 收集所有 Worker name
        Set<String> workerNames = plan.getWorkers().stream()
                .map(WorkerPlan::getName)
                .collect(Collectors.toSet());

        for (WorkerPlan wp : plan.getWorkers()) {
            // name 不能为空
            if (wp.getName() == null || wp.getName().isBlank()) {
                wp.setName("worker_" + UUID.randomUUID().toString().substring(0, 4));
            }

            // toolsNeeded 默认给 file_write
            if (wp.getToolsNeeded() == null || wp.getToolsNeeded().isEmpty()) {
                wp.setToolsNeeded(List.of("file_write", "file_read"));
            }

            // depends_on 中引用不存在的 Worker → 移除无效引用
            if (wp.getDependsOn() != null) {
                List<String> valid = wp.getDependsOn().stream()
                        .filter(workerNames::contains)
                        .filter(dep -> !dep.equals(wp.getName())) // 不能依赖自己
                        .toList();
                wp.setDependsOn(valid);
            } else {
                wp.setDependsOn(List.of());
            }
        }

        // 执行策略默认值
        if (plan.getExecutionStrategy() == null || plan.getExecutionStrategy().isBlank()) {
            plan.setExecutionStrategy("parallel");
        }

        // DAG 环检测
        if ("dag".equals(plan.getExecutionStrategy())) {
            if (hasCircularDependency(plan.getWorkers())) {
                log.warn("[Manager] DAG has circular dependency, falling back to parallel");
                plan.setExecutionStrategy("parallel");
                plan.getWorkers().forEach(wp -> wp.setDependsOn(List.of()));
            }
        }
    }

    // ============================================================
    // Phase 3: 按策略执行 Workers
    // ============================================================

    /**
     * 根据 execution_strategy 选择执行方式。
     */
    private List<WorkerResult> executeWorkers(List<SpawnedWorker> workers,
                                               TaskPlan plan,
                                               StreamCallback callback) {
        String strategy = plan.getExecutionStrategy() != null
                ? plan.getExecutionStrategy() : "parallel";

        log.info("[Manager] Executing {} workers with strategy: {}", workers.size(), strategy);

        return switch (strategy) {
            case "sequential" -> executeSequential(workers, plan.getWorkers(), callback);
            case "dag"        -> executeDAG(workers, plan.getWorkers(), callback);
            default           -> executeParallel(workers, callback);
        };
    }

    // ----- Parallel: 所有 Worker 同时启动 -----

    /**
     * 并行执行所有 Workers — 使用 CompletableFuture 并发调度。
     * 适用于子任务之间完全独立的场景。
     */
    private List<WorkerResult> executeParallel(List<SpawnedWorker> workers,
                                                StreamCallback callback) {
        callback.onEvent("strategy", "策略: 并行执行 — 所有 Worker 同时启动");

        List<CompletableFuture<WorkerResult>> futures = workers.stream()
                .map(worker -> CompletableFuture.supplyAsync(
                                () -> executeOneWorker(worker, callback),
                                workerExecutor)
                        .orTimeout(workerTimeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(ex -> WorkerResult.failure(worker.getName(),
                                "Worker timed out after " + workerTimeoutSeconds + "s: " + ex.getMessage())))
                .toList();

        // 等待所有 Worker 完成
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    // ----- Sequential: 按 priority 排序后逐个执行 -----

    /**
     * 串行执行 Workers — 按 priority 升序排列，逐个执行。
     * 适用于有严格先后顺序的场景。
     */
    private List<WorkerResult> executeSequential(List<SpawnedWorker> workers,
                                                  List<WorkerPlan> plans,
                                                  StreamCallback callback) {
        callback.onEvent("strategy", "策略: 串行执行 — 按优先级顺序逐个执行");

        // 按 priority 排序（建立 name → priority 映射）
        Map<String, Integer> priorityMap = new HashMap<>();
        for (WorkerPlan wp : plans) {
            priorityMap.put(wp.getName(), wp.getPriority());
        }

        List<SpawnedWorker> sorted = new ArrayList<>(workers);
        sorted.sort(Comparator.comparingInt(w -> priorityMap.getOrDefault(w.getName(), 999)));

        List<WorkerResult> results = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            SpawnedWorker worker = sorted.get(i);
            callback.onEvent("sequential_progress", String.format(
                    "串行进度: [%d/%d] 正在执行 Worker [%s]",
                    i + 1, sorted.size(), worker.getName()));

            WorkerResult result = executeOneWorker(worker, callback);
            results.add(result);

            // 如果某个 Worker 失败，后续 Worker 仍然继续（Manager 最终汇总时会处理）
            if (!result.isSuccess()) {
                callback.onEvent("sequential_warning", String.format(
                        "Worker [%s] 失败，继续执行后续 Worker", worker.getName()));
            }
        }

        return results;
    }

    // ----- DAG: 按 depends_on 拓扑排序执行 -----

    /**
     * DAG 执行 — 按 depends_on 构建有向无环图，拓扑排序后分层并行执行。
     *
     * 算法：Kahn's algorithm（BFS 拓扑排序）
     * - 同一层（入度为 0）的 Worker 并行执行
     * - 一层完成后，将其后继的入度减 1，释放下一层
     * - 直到所有 Worker 执行完毕
     *
     * 示例 DAG:
     *   researcher ──→ analyst ──→ report_writer
     *   data_collector ─┘
     *
     *   Layer 0: [researcher, data_collector] — 并行
     *   Layer 1: [analyst]                    — 等 Layer 0 完成
     *   Layer 2: [report_writer]              — 等 Layer 1 完成
     */
    private List<WorkerResult> executeDAG(List<SpawnedWorker> workers,
                                           List<WorkerPlan> plans,
                                           StreamCallback callback) {
        callback.onEvent("strategy", "策略: DAG 执行 — 按依赖关系分层并行");

        // 建立 name → SpawnedWorker 映射
        Map<String, SpawnedWorker> workerMap = new LinkedHashMap<>();
        for (SpawnedWorker w : workers) {
            workerMap.put(w.getName(), w);
        }

        // 建立 name → dependsOn 映射
        Map<String, List<String>> dependsOnMap = new LinkedHashMap<>();
        for (WorkerPlan wp : plans) {
            dependsOnMap.put(wp.getName(), wp.getDependsOn() != null ? wp.getDependsOn() : List.of());
        }

        // Kahn's algorithm — 计算入度
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new LinkedHashMap<>(); // name → 后继列表

        for (WorkerPlan wp : plans) {
            inDegree.put(wp.getName(), 0);
            successors.put(wp.getName(), new ArrayList<>());
        }

        for (WorkerPlan wp : plans) {
            List<String> deps = dependsOnMap.getOrDefault(wp.getName(), List.of());
            inDegree.put(wp.getName(), deps.size());
            for (String dep : deps) {
                successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(wp.getName());
            }
        }

        // 分层执行
        List<WorkerResult> allResults = new ArrayList<>();
        Map<String, WorkerResult> resultMap = new ConcurrentHashMap<>();
        int layerIndex = 0;

        while (!inDegree.isEmpty()) {
            // 找出当前入度为 0 的节点 = 本层可执行的 Worker
            List<String> ready = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() == 0)
                    .map(Map.Entry::getKey)
                    .toList();

            if (ready.isEmpty()) {
                // 不应该到这里（环已在 validatePlan 中检测），但防御性处理
                log.error("[Manager] DAG deadlock: remaining workers have unresolved dependencies: {}",
                        inDegree.keySet());
                callback.onEvent("dag_error", "DAG 死锁: " + inDegree.keySet());
                break;
            }

            layerIndex++;
            int layer = layerIndex;
            callback.onEvent("dag_layer", String.format(
                    "DAG 第 %d 层: 并行执行 %s", layer, ready));

            // 从入度表中移除本层节点
            ready.forEach(inDegree::remove);

            // 并行执行本层 Workers
            List<CompletableFuture<WorkerResult>> layerFutures = ready.stream()
                    .map(name -> {
                        SpawnedWorker w = workerMap.get(name);
                        if (w == null) {
                            log.warn("[Manager] DAG: worker '{}' not found in spawned workers", name);
                            return CompletableFuture.completedFuture(
                                    WorkerResult.failure(name, "Worker not found in spawned list"));
                        }
                        return CompletableFuture.supplyAsync(
                                        () -> executeOneWorker(w, callback),
                                        workerExecutor)
                                .orTimeout(workerTimeoutSeconds, TimeUnit.SECONDS)
                                .exceptionally(ex -> WorkerResult.failure(name,
                                        "Worker timed out after " + workerTimeoutSeconds + "s: " + ex.getMessage()));
                    })
                    .toList();

            // 等待本层全部完成
            List<WorkerResult> layerResults = layerFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            allResults.addAll(layerResults);
            for (WorkerResult r : layerResults) {
                resultMap.put(r.getWorkerName(), r);
            }

            // 减少后继节点的入度
            for (String finishedName : ready) {
                List<String> succs = successors.getOrDefault(finishedName, List.of());
                for (String succ : succs) {
                    inDegree.computeIfPresent(succ, (k, v) -> v - 1);
                }
            }

            callback.onEvent("dag_layer_done", String.format(
                    "DAG 第 %d 层完成: %s", layer, ready));
        }

        return allResults;
    }

    // ----- 单个 Worker 执行（公共逻辑） -----

    /**
     * 执行单个 Worker 并推送事件。
     * 被 parallel / sequential / dag 三种策略共用。
     */
    private WorkerResult executeOneWorker(SpawnedWorker worker, StreamCallback callback) {
        String name = worker.getName();

        callback.onEvent("worker_start", String.format(
                "[%s] 开始执行", name));

        log.info("[Manager] Worker [{}] started execution", name);
        long start = System.currentTimeMillis();

        WorkerResult result = workerEngine.execute(worker);

        long elapsed = System.currentTimeMillis() - start;

        if (result.isSuccess()) {
            callback.onEvent("worker_done", String.format(
                    "[%s] 执行成功 (%dms)", name, elapsed));
            log.info("[Manager] Worker [{}] completed in {}ms", name, elapsed);
        } else {
            callback.onEvent("worker_done", String.format(
                    "[%s] 执行失败 (%dms): %s", name, elapsed, result.getError()));
            log.warn("[Manager] Worker [{}] failed in {}ms: {}", name, elapsed, result.getError());
        }

        return result;
    }

    // ============================================================
    // Phase 5: 用后即焚 — 销毁 Worker 工作目录
    // ============================================================

    /**
     * 销毁 Worker 工作目录（递归 rm -rf）并更新 DB 状态为 DESTROYED。
     * DNA 快照已在 Phase 2 持久化到 SQLite，即使目录删除也可追溯。
     */
    private void destroyWorker(SpawnedWorker worker, StreamCallback callback) {
        try {
            Path dir = Path.of(worker.getDir());
            if (Files.exists(dir)) {
                // 递归删除（深度优先，先删文件后删目录）
                try (var paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }

                // 更新 DB 状态
                workerAgentRepo.updateStatus(worker.getId(), "DESTROYED");
                workerAgentRepo.updateDestroyedAt(worker.getId(), Instant.now().toString());

                callback.onEvent("worker_destroyed", String.format(
                        "[%s] 已销毁 — %s", worker.getName(), worker.getDir()));
                log.info("[Manager] Worker [{}] destroyed: {}", worker.getName(), worker.getDir());
            }
        } catch (IOException e) {
            log.error("[Manager] Failed to destroy worker dir: {}", worker.getDir(), e);
            callback.onEvent("destroy_error", String.format(
                    "[%s] 销毁失败: %s", worker.getName(), e.getMessage()));
        }
    }

    /**
     * 尝试删除任务级目录（/tmp/hive/{taskId}/）。
     * 只有在目录为空时才删除。
     */
    private void destroyTaskDir(String taskId) {
        try {
            Path taskDir = Path.of(System.getProperty("hiveforge.hive-base-dir", "/tmp/hive"), taskId);
            if (Files.exists(taskDir) && isDirectoryEmpty(taskDir)) {
                Files.delete(taskDir);
                log.info("[Manager] Task directory cleaned: {}", taskDir);
            }
        } catch (IOException e) {
            log.debug("[Manager] Could not clean task dir for {}: {}", taskId, e.getMessage());
        }
    }

    private boolean isDirectoryEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * DAG 环检测 — DFS 检测有向图中是否存在环。
     */
    private boolean hasCircularDependency(List<WorkerPlan> workers) {
        Map<String, List<String>> graph = new HashMap<>();
        Set<String> allNames = new HashSet<>();

        for (WorkerPlan wp : workers) {
            allNames.add(wp.getName());
            graph.put(wp.getName(), wp.getDependsOn() != null ? wp.getDependsOn() : List.of());
        }

        // DFS 三色标记: 0=white, 1=gray(processing), 2=black(done)
        Map<String, Integer> color = new HashMap<>();
        allNames.forEach(name -> color.put(name, 0));

        for (String name : allNames) {
            if (color.get(name) == 0) {
                if (dfsFindCycle(name, graph, color)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dfsFindCycle(String node, Map<String, List<String>> graph,
                                  Map<String, Integer> color) {
        color.put(node, 1); // gray — processing
        for (String dep : graph.getOrDefault(node, List.of())) {
            Integer depColor = color.get(dep);
            if (depColor == null) continue; // dep 不在图中，跳过
            if (depColor == 1) return true; // back edge → cycle
            if (depColor == 0 && dfsFindCycle(dep, graph, color)) return true;
        }
        color.put(node, 2); // black — done
        return false;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Manager] JSON serialization failed", e);
            return "{}";
        }
    }

    /**
     * 将最终报告保存为 Markdown 文件到 data/reports/ 目录。
     * 文件名: {taskId}.md，包含任务元信息头 + 报告正文。
     */
    private Path saveReportToFile(String taskId, String userQuery, String report) {
        try {
            Path reportsDir = Path.of("data", "reports");
            Files.createDirectories(reportsDir);

            String markdown = "# HiveForge Report\n\n"
                    + "- **Task ID**: " + taskId + "\n"
                    + "- **Query**: " + userQuery + "\n"
                    + "- **Time**: " + Instant.now() + "\n\n"
                    + "---\n\n"
                    + report;

            Path reportFile = reportsDir.resolve(taskId + ".md");
            Files.writeString(reportFile, markdown);

            log.info("[Manager] Report saved to: {}", reportFile.toAbsolutePath());
            return reportFile;
        } catch (IOException e) {
            log.error("[Manager] Failed to save report file for task {}", taskId, e);
            return Path.of("data/reports/" + taskId + ".md");
        }
    }

    @PreDestroy
    public void shutdown() {
        workerExecutor.shutdown();
    }
}
