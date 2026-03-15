package com.hiveforge.spawner;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.manager.WorkerPlan;
import com.hiveforge.repository.DnaTemplateRepository;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class WorkerSpawner {

    private static final Logger log = LoggerFactory.getLogger(WorkerSpawner.class);

    private final LlmClient llmClient;
    private final DnaTemplateRepository templateRepo;
    private final WorkerAgentRepository workerRepo;
    private final ToolRegistry toolRegistry;

    @Value("${hiveforge.hive-base-dir:/tmp/hive}")
    private String hiveBaseDir;

    private static final String DNA_GENERATION_PROMPT = """
        You are an Agent architect. You need to write DNA files for a Worker Agent.

        ## Worker info
        - Role: %s
        - Available tools: %s
        - Task: %s
        - User context: %s

        ## Output requirements
        Generate three Markdown files, separated by === markers:

        === SOUL.md ===
        (Define personality, professional background, behavioral guidelines, output format)

        === AGENTS.md ===
        (Declare available tools and usage rules, applicable scenarios for each tool)

        === TASK.md ===
        (Specify task objectives, concrete steps, deliverables, constraints)

        Requirements:
        - SOUL.md should give the Worker a distinct professional persona
        - AGENTS.md should only declare tools the Worker actually needs
        - TASK.md should be very specific with measurable completion criteria
        """;

    public WorkerSpawner(LlmClient llmClient,
                         DnaTemplateRepository templateRepo,
                         WorkerAgentRepository workerRepo,
                         ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.templateRepo = templateRepo;
        this.workerRepo = workerRepo;
        this.toolRegistry = toolRegistry;
    }

    public SpawnedWorker spawn(String taskId, WorkerPlan plan, String userContext) {
        String workerId = UUID.randomUUID().toString().substring(0, 8);
        String workerName = plan.getName();

        // 1. Create working directory
        Path workerDir = Path.of(hiveBaseDir, taskId, workerName + "_" + workerId);
        createDirectory(workerDir);

        // 2. Generate DNA
        AgentDna dna;
        if (plan.getTemplate() != null) {
            dna = generateFromTemplate(plan, userContext);
        } else {
            dna = generateFromScratch(plan, userContext);
        }

        // 3. Write to filesystem — Agent is born
        writeFile(workerDir.resolve("SOUL.md"), dna.getSoulMd());
        writeFile(workerDir.resolve("AGENTS.md"), dna.getAgentsMd());
        writeFile(workerDir.resolve("TASK.md"), dna.getTaskMd());

        if (userContext != null && !userContext.isEmpty()) {
            writeFile(workerDir.resolve("USER.md"), "# User Context\n\n" + userContext);
        }

        createDirectory(workerDir.resolve("output"));

        // 4. Persist Worker record with DNA snapshot
        workerRepo.save(workerId, taskId, workerName, workerDir.toString(),
                dna.getSoulMd(), dna.getAgentsMd(), dna.getTaskMd());

        log.info("[Spawner] Worker born: {} at {}", workerName, workerDir);

        return new SpawnedWorker(workerId, workerName, workerDir.toString(),
                dna, plan.getToolsNeeded());
    }

    private AgentDna generateFromScratch(WorkerPlan plan, String userContext) {
        String toolsDesc = plan.getToolsNeeded().stream()
                .map(toolRegistry::getDescription)
                .reduce("", (a, b) -> a + "\n" + b);

        String prompt = String.format(DNA_GENERATION_PROMPT,
                plan.getRole(),
                toolsDesc,
                plan.getTaskDescription(),
                userContext != null ? userContext : "none");

        String response = llmClient.chat(prompt);
        return parseDna(response);
    }

    private AgentDna generateFromTemplate(WorkerPlan plan, String userContext) {
        var template = templateRepo.findByName(plan.getTemplate());
        if (template == null) {
            return generateFromScratch(plan, userContext);
        }

        String soul = template.soulTemplate()
                .replace("{{task}}", plan.getTaskDescription())
                .replace("{{context}}", userContext != null ? userContext : "");

        String agents = template.agentsTemplate()
                .replace("{{tools}}", String.join(", ", plan.getToolsNeeded()));

        String taskMd = "# Task\n\n## Objective\n" + plan.getTaskDescription()
                + "\n\n## Deliverable\nWrite results to ./output/\n";

        return new AgentDna(soul, agents, taskMd);
    }

    private AgentDna parseDna(String response) {
        String[] parts = response.split("===\\s*\\w+\\.md\\s*===");

        String soul = parts.length > 1 ? parts[1].trim() : "# Default Soul\nYou are a general assistant.";
        String agents = parts.length > 2 ? parts[2].trim() : "# Default Agents\nNo special tools.";
        String task = parts.length > 3 ? parts[3].trim() : "# Default Task\nComplete the assigned task.";

        return new AgentDna(soul, agents, task);
    }

    private void createDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + path, e);
        }
    }
}
