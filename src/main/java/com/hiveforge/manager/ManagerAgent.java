package com.hiveforge.manager;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.llm.StreamCallback;
import com.hiveforge.repository.DnaTemplateRepository;
import com.hiveforge.repository.HiveTaskRepository;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.spawner.SpawnedWorker;
import com.hiveforge.spawner.WorkerSpawner;
import com.hiveforge.worker.WorkerEngine;
import com.hiveforge.worker.WorkerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    private static final String PLANNING_PROMPT = """
        You are a task planning expert. Analyze the user's complex task and decompose it
        into multiple professional sub-tasks, each executed by an independent Worker Agent.

        ## Available Worker type templates
        %s

        ## User task
        %s

        ## Output format (strict JSON)
        {
          "task_summary": "task overview",
          "workers": [
            {
              "name": "worker name in english e.g. financial_analyst",
              "role": "role description",
              "tools_needed": ["web_search", "calculate"],
              "task_description": "specific task for this Worker",
              "depends_on": [],
              "priority": 1,
              "template": "template name or null for auto-generation"
            }
          ],
          "execution_strategy": "parallel or sequential or dag",
          "aggregation_instruction": "how to merge Worker results into final report"
        }
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

    /**
     * Main entry point for hive task execution.
     */
    public HiveResult executeHive(String userQuery, StreamCallback callback) {
        String taskId = java.util.UUID.randomUUID().toString();
        HiveTask task = new HiveTask(taskId, userQuery);
        taskRepo.save(task);

        try {
            callback.onEvent("hive_start", "Hive task started: " + taskId);

            // Phase 1: Plan
            callback.onEvent("planning", "Manager is analyzing and planning workers...");
            task.setStatus("PLANNING");

            TaskPlan plan = planTask(userQuery);
            task.setTaskPlan(toJson(plan));
            task.setWorkerCount(plan.getWorkers().size());
            taskRepo.update(task);

            callback.onEvent("plan_ready",
                    "Task decomposed into " + plan.getWorkers().size() + " workers");

            // Phase 2: Spawn workers
            callback.onEvent("spawning", "Generating Worker DNA...");
            task.setStatus("SPAWNING");
            taskRepo.update(task);

            List<SpawnedWorker> workers = new ArrayList<>();
            for (WorkerPlan wp : plan.getWorkers()) {
                SpawnedWorker worker = workerSpawner.spawn(taskId, wp, userQuery);
                workers.add(worker);
                callback.onEvent("worker_spawned",
                        "Worker [" + worker.getName() + "] spawned at " + worker.getDir());
            }

            // Phase 3: Execute
            callback.onEvent("executing", "Workers executing tasks...");
            task.setStatus("EXECUTING");
            taskRepo.update(task);

            List<WorkerResult> results = executeWorkers(
                    workers, plan.getExecutionStrategy(), callback);

            // Phase 4: Aggregate
            callback.onEvent("aggregating", "Manager aggregating all worker results...");
            task.setStatus("AGGREGATING");
            taskRepo.update(task);

            String finalReport = resultAggregator.aggregate(
                    userQuery, plan, results, callback);

            // Phase 5: Destroy
            callback.onEvent("destroying", "Cleaning up worker directories...");
            for (SpawnedWorker worker : workers) {
                destroyWorker(worker, callback);
            }

            // Complete
            task.setStatus("COMPLETED");
            task.setFinalReport(finalReport);
            task.setCompletedAt(Instant.now().toString());
            taskRepo.update(task);

            callback.onEvent("hive_complete", "Hive task completed");

            return new HiveResult(taskId, finalReport, results);

        } catch (Exception e) {
            log.error("Hive task failed: {}", taskId, e);
            task.setStatus("FAILED");
            taskRepo.update(task);
            callback.onEvent("hive_error", "Hive task failed: " + e.getMessage());
            throw new RuntimeException("Hive execution failed", e);
        }
    }

    private TaskPlan planTask(String userQuery) {
        var templates = templateRepo.findAll();
        String templateDesc = templates.stream()
                .map(t -> "- " + t.templateName() + ": " + t.description())
                .reduce("", (a, b) -> a + "\n" + b);

        String prompt = String.format(PLANNING_PROMPT, templateDesc, userQuery);
        String response = llmClient.chat(prompt);
        return parseTaskPlan(response);
    }

    private TaskPlan parseTaskPlan(String json) {
        try {
            // Extract JSON from potential markdown code block
            String cleaned = json;
            if (cleaned.contains("```json")) {
                cleaned = cleaned.substring(cleaned.indexOf("```json") + 7);
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            } else if (cleaned.contains("```")) {
                cleaned = cleaned.substring(cleaned.indexOf("```") + 3);
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            }
            return objectMapper.readValue(cleaned.trim(), TaskPlan.class);
        } catch (Exception e) {
            log.error("Failed to parse task plan: {}", json, e);
            throw new RuntimeException("Failed to parse task plan from LLM response", e);
        }
    }

    private List<WorkerResult> executeWorkers(List<SpawnedWorker> workers,
                                               String strategy,
                                               StreamCallback callback) {
        return switch (strategy != null ? strategy : "parallel") {
            case "sequential" -> executeSequential(workers, callback);
            case "dag" -> executeParallel(workers, callback); // DAG simplified to parallel for now
            default -> executeParallel(workers, callback);
        };
    }

    private List<WorkerResult> executeParallel(List<SpawnedWorker> workers,
                                                StreamCallback callback) {
        List<CompletableFuture<WorkerResult>> futures = workers.stream()
                .map(worker -> CompletableFuture.supplyAsync(() -> {
                    callback.onEvent("worker_start",
                            "[" + worker.getName() + "] started");
                    WorkerResult result = workerEngine.execute(worker);
                    callback.onEvent("worker_done",
                            (result.isSuccess() ? "[OK]" : "[FAIL]") + " ["
                                    + worker.getName() + "] "
                                    + (result.isSuccess() ? "completed" : "failed: " + result.getError()));
                    return result;
                }))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private List<WorkerResult> executeSequential(List<SpawnedWorker> workers,
                                                  StreamCallback callback) {
        List<WorkerResult> results = new ArrayList<>();
        for (SpawnedWorker worker : workers) {
            callback.onEvent("worker_start", "[" + worker.getName() + "] started");
            WorkerResult result = workerEngine.execute(worker);
            callback.onEvent("worker_done",
                    (result.isSuccess() ? "[OK]" : "[FAIL]") + " [" + worker.getName() + "]");
            results.add(result);
        }
        return results;
    }

    private void destroyWorker(SpawnedWorker worker, StreamCallback callback) {
        try {
            Path dir = Path.of(worker.getDir());
            if (Files.exists(dir)) {
                try (var paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
                workerAgentRepo.updateStatus(worker.getId(), "DESTROYED");
                workerAgentRepo.updateDestroyedAt(worker.getId(), Instant.now().toString());
                callback.onEvent("worker_destroyed", "[" + worker.getName() + "] destroyed");
            }
        } catch (IOException e) {
            log.error("Failed to destroy worker dir: {}", worker.getDir(), e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
