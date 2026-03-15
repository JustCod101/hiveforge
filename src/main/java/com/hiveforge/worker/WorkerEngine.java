package com.hiveforge.worker;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.repository.WorkerTraceRepository;
import com.hiveforge.spawner.SpawnedWorker;
import com.hiveforge.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Service
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);
    private static final int MAX_TOOL_ROUNDS = 10;

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final WorkerTraceRepository traceRepo;
    private final WorkerAgentRepository workerRepo;

    public WorkerEngine(LlmClient llmClient,
                        ToolExecutor toolExecutor,
                        WorkerTraceRepository traceRepo,
                        WorkerAgentRepository workerRepo) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.traceRepo = traceRepo;
        this.workerRepo = workerRepo;
    }

    public WorkerResult execute(SpawnedWorker worker) {
        Path workerDir = Path.of(worker.getDir());
        long startTime = System.nanoTime();

        try {
            // 1. Read DNA — soul loading
            String soulMd = readFile(workerDir.resolve("SOUL.md"));
            String agentsMd = readFile(workerDir.resolve("AGENTS.md"));
            String taskMd = readFile(workerDir.resolve("TASK.md"));
            String userMd = readFileIfExists(workerDir.resolve("USER.md"));

            workerRepo.updateStatus(worker.getId(), "INITIALIZING");

            // 2. Assemble system prompt
            String systemPrompt = assembleSystemPrompt(soulMd, agentsMd, taskMd, userMd, workerDir);

            // 3. Execute via LLM (simplified single-turn for now)
            workerRepo.updateStatus(worker.getId(), "EXECUTING");
            workerRepo.updateStartedAt(worker.getId(), Instant.now().toString());

            int stepIndex = 0;

            // Send task to LLM
            String userMessage = "Please execute the task described in TASK.md. " +
                    "Think step by step and produce the deliverable.";

            String response = llmClient.chatWithSystem(systemPrompt, userMessage);

            // Record thought trace
            traceRepo.save(worker.getId(), ++stepIndex, "THOUGHT", response, null, null, null);

            // Write result to output directory
            Path outputFile = workerDir.resolve("output/result.md");
            Files.writeString(outputFile, response);

            // Read all output files
            String fullOutput = readOutputFiles(workerDir.resolve("output"));

            long executionMs = (System.nanoTime() - startTime) / 1_000_000;
            workerRepo.updateCompleted(worker.getId(), fullOutput, 0, 1, executionMs);

            return WorkerResult.success(worker.getName(), fullOutput);

        } catch (Exception e) {
            long executionMs = (System.nanoTime() - startTime) / 1_000_000;
            workerRepo.updateFailed(worker.getId(), e.getMessage(), executionMs);
            log.error("[Worker {}] execution failed", worker.getName(), e);
            return WorkerResult.failure(worker.getName(), e.getMessage());
        }
    }

    private String assembleSystemPrompt(String soul, String agents,
                                         String task, String user, Path workerDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Your Identity & Persona\n\n").append(soul).append("\n\n");
        sb.append("# Your Capabilities & Tools\n\n").append(agents).append("\n\n");
        sb.append("# Your Task\n\n").append(task).append("\n\n");

        if (user != null) {
            sb.append("# User Background\n\n").append(user).append("\n\n");
        }

        sb.append("# Working Rules\n\n");
        sb.append("- Your working directory is: ").append(workerDir).append("\n");
        sb.append("- Write final deliverables to ./output/ directory\n");
        sb.append("- Output a concise result summary when done\n");
        sb.append("- If you encounter difficulties, explain the reason in your output\n");

        return sb.toString();
    }

    private String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    private String readFileIfExists(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (IOException e) {
            log.warn("Failed to read optional file: {}", path);
        }
        return null;
    }

    private String readOutputFiles(Path outputDir) {
        if (!Files.exists(outputDir)) return "";

        try (var paths = Files.walk(outputDir, 1)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(p -> {
                        try {
                            return "### " + p.getFileName() + "\n\n" + Files.readString(p);
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .reduce("", (a, b) -> a + "\n\n" + b)
                    .trim();
        } catch (IOException e) {
            return "";
        }
    }
}
