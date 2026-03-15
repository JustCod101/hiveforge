package com.hiveforge.worker;

import com.hiveforge.llm.*;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.repository.WorkerTraceRepository;
import com.hiveforge.spawner.SpawnedWorker;
import com.hiveforge.tool.ToolExecutor;
import com.hiveforge.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Worker Agent Engine — Worker 的"灵魂载入"与 ReAct 执行引擎。
 *
 * 核心流程:
 * 1. 读取工作目录中的 SOUL.md / AGENTS.md / TASK.md / USER.md
 * 2. 组装为 System Prompt（人格 → 工具 → 任务 → 规则）
 * 3. 只注册 AGENTS.md 声明的工具（通过 SpawnedWorker.toolsNeeded 过滤）
 * 4. ReAct 循环（max 10 轮）：LLM → Tool Call → Observation → 继续
 * 5. 工具执行上下文限定在 Worker 工作目录内（沙盒隔离）
 * 6. 读取 output/ 目录中的结果文件
 * 7. 全过程 Trace 持久化（THOUGHT / ACTION / OBSERVATION / REFLECTION）
 */
@Service
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);

    /** ReAct 循环最大轮数 — 每轮 = 一次 LLM 调用 + 可能的多个 Tool Call */
    private static final int MAX_REACT_ROUNDS = 10;

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

    /**
     * 执行一个 Worker Agent — 完整的 ReAct 循环。
     *
     * @param worker 已孕育的 Worker（包含 ID、目录、DNA、工具列表）
     * @return 执行结果（成功或失败）
     */
    public WorkerResult execute(SpawnedWorker worker) {
        Path workerDir = Path.of(worker.getDir());
        long startTime = System.nanoTime();
        int totalToolCalls = 0;
        int totalLlmCalls = 0;

        try {
            // ============================================================
            // Phase 1: 灵魂载入 — 读取 DNA 文件
            // ============================================================
            log.info("[Worker:{}] Loading soul from {}", worker.getName(), workerDir);

            String soulMd = readFile(workerDir.resolve("SOUL.md"));
            String agentsMd = readFile(workerDir.resolve("AGENTS.md"));
            String taskMd = readFile(workerDir.resolve("TASK.md"));
            String userMd = readFileIfExists(workerDir.resolve("USER.md"));

            workerRepo.updateStatus(worker.getId(), "INITIALIZING");

            // ============================================================
            // Phase 2: 组装 System Prompt — 人格→工具→任务→规则
            // ============================================================
            String systemPrompt = assembleSystemPrompt(soulMd, agentsMd, taskMd, userMd, workerDir);

            // ============================================================
            // Phase 3: 注册工具 — 只注册 AGENTS.md 声明的工具
            // ============================================================
            // 从 SpawnedWorker.toolsNeeded 过滤，只把该 Worker 有权使用的工具发给 LLM
            List<ToolDefinition> allowedTools = resolveAllowedTools(worker.getToolsNeeded());
            log.info("[Worker:{}] Registered {} tools: {}", worker.getName(),
                    allowedTools.size(),
                    allowedTools.stream().map(ToolDefinition::getName).toList());

            // ============================================================
            // Phase 4: ReAct 循环
            // ============================================================
            workerRepo.updateStatus(worker.getId(), "EXECUTING");
            workerRepo.updateStartedAt(worker.getId(), Instant.now().toString());

            // 初始化对话历史
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(systemPrompt));
            messages.add(ChatMessage.user(
                    "请开始执行 TASK.md 中描述的任务。逐步思考，使用可用工具完成任务，"
                    + "最终将成果写入 ./output/ 目录并输出结果摘要。"));

            StringBuilder fullOutput = new StringBuilder();
            int stepIndex = 0;

            for (int round = 0; round < MAX_REACT_ROUNDS; round++) {
                log.info("[Worker:{}] ReAct round {}/{}", worker.getName(), round + 1, MAX_REACT_ROUNDS);

                // --- LLM 调用 ---
                long llmStart = System.currentTimeMillis();
                ChatResponse response = llmClient.chatWithWorkerModel(messages, allowedTools);
                long llmLatency = System.currentTimeMillis() - llmStart;
                totalLlmCalls++;

                String content = response.getContent();
                List<ToolCall> toolCalls = response.getToolCalls();

                // --- 记录 THOUGHT（LLM 的思考/文本输出）---
                if (content != null && !content.isBlank()) {
                    traceRepo.saveWithLatency(worker.getId(), ++stepIndex,
                            "THOUGHT", content, null, null, null, llmLatency);
                    fullOutput.append(content).append("\n\n");
                    log.debug("[Worker:{}] THOUGHT: {}", worker.getName(),
                            content.length() > 200 ? content.substring(0, 200) + "..." : content);
                }

                // --- 无 Tool Call → 任务完成 ---
                if (!response.hasToolCalls()) {
                    log.info("[Worker:{}] No more tool calls, finishing after {} rounds",
                            worker.getName(), round + 1);

                    // 记录 REFLECTION（最终总结）
                    if (content != null && !content.isBlank()) {
                        traceRepo.save(worker.getId(), ++stepIndex,
                                "REFLECTION", "Task completed after " + (round + 1) + " rounds, "
                                + totalToolCalls + " tool calls, " + totalLlmCalls + " LLM calls.",
                                null, null, null);
                    }
                    break;
                }

                // --- 将 assistant 消息（含 tool_calls）加入对话历史 ---
                messages.add(ChatMessage.assistant(content, toolCalls));

                // --- 执行每个 Tool Call ---
                for (ToolCall call : toolCalls) {
                    totalToolCalls++;

                    // 记录 ACTION
                    traceRepo.save(worker.getId(), ++stepIndex,
                            "ACTION", "Calling tool: " + call.getName(),
                            call.getName(), call.getArguments(), null);

                    log.info("[Worker:{}] ACTION: {}({})", worker.getName(),
                            call.getName(),
                            call.getArguments().length() > 100
                                    ? call.getArguments().substring(0, 100) + "..."
                                    : call.getArguments());

                    // 工具执行 — 上下文限定在 Worker 工作目录内
                    long toolStart = System.currentTimeMillis();
                    ToolResult toolResult = toolExecutor.executeInContext(
                            call.getName(), call.getArguments(), worker.getDir());
                    long toolLatency = System.currentTimeMillis() - toolStart;

                    String observation = toolResult.isSuccess()
                            ? toolResult.getOutput()
                            : "[ERROR] " + toolResult.getOutput();

                    // 记录 OBSERVATION
                    traceRepo.saveWithLatency(worker.getId(), ++stepIndex,
                            "OBSERVATION", observation,
                            call.getName(), call.getArguments(),
                            toolResult.getOutput(), toolLatency);

                    log.info("[Worker:{}] OBSERVATION: {} ({}ms, success={})",
                            worker.getName(), call.getName(), toolLatency, toolResult.isSuccess());

                    // 将 tool 结果加入对话历史（关联 tool_call_id）
                    messages.add(ChatMessage.toolResult(call.getId(), observation));
                }
            }

            // ============================================================
            // Phase 5: 收集结果 — 读取 output/ 目录
            // ============================================================
            String outputResult = readOutputFiles(workerDir.resolve("output"));
            if (!outputResult.isBlank()) {
                fullOutput.append("\n\n---\n# Output Files\n\n").append(outputResult);
            }

            // 更新数据库状态
            long executionMs = (System.nanoTime() - startTime) / 1_000_000;
            workerRepo.updateCompleted(worker.getId(),
                    fullOutput.toString(), totalToolCalls, totalLlmCalls, executionMs);

            log.info("[Worker:{}] Completed in {}ms, {} LLM calls, {} tool calls",
                    worker.getName(), executionMs, totalLlmCalls, totalToolCalls);

            return WorkerResult.success(worker.getName(), fullOutput.toString());

        } catch (Exception e) {
            long executionMs = (System.nanoTime() - startTime) / 1_000_000;
            workerRepo.updateFailed(worker.getId(), e.getMessage(), executionMs);
            log.error("[Worker:{}] Execution failed after {}ms", worker.getName(), executionMs, e);
            return WorkerResult.failure(worker.getName(), e.getMessage());
        }
    }

    // ============================================================
    // System Prompt 组装
    // ============================================================

    /**
     * 组装 System Prompt — 将 DNA 文件融合为完整指令。
     *
     * 结构：
     * 1. 身份与人格（SOUL.md）
     * 2. 能力与工具（AGENTS.md）
     * 3. 任务目标（TASK.md）
     * 4. 用户背景（USER.md，可选）
     * 5. 工作规则（框架注入，不可被 Agent 覆盖）
     */
    private String assembleSystemPrompt(String soul, String agents,
                                         String task, String user, Path workerDir) {
        StringBuilder sb = new StringBuilder();

        // 人格
        sb.append("# 你的身份与人格\n\n").append(soul).append("\n\n");

        // 工具
        sb.append("# 你的能力与工具\n\n").append(agents).append("\n\n");

        // 任务
        sb.append("# 你的任务\n\n").append(task).append("\n\n");

        // 用户上下文（可选）
        if (user != null && !user.isBlank()) {
            sb.append("# 用户背景\n\n").append(user).append("\n\n");
        }

        // 框架注入的工作规则（不可被 Agent DNA 覆盖）
        sb.append("# 工作规则（系统级，不可覆盖）\n\n");
        sb.append("- 你的工作目录是: `").append(workerDir).append("`\n");
        sb.append("- 所有文件操作的路径都相对于工作目录\n");
        sb.append("- 将最终成果写入 `./output/` 目录下\n");
        sb.append("- 完成任务后，输出一段简洁的结果摘要\n");
        sb.append("- 如果遇到困难或信息不足，在输出中明确说明原因\n");
        sb.append("- 你只能使用上面声明的工具，不要编造不存在的工具\n");
        sb.append("- 如果当前轮次无法获取所需信息，用已有信息完成尽可能多的工作\n");

        return sb.toString();
    }

    // ============================================================
    // 工具过滤
    // ============================================================

    /**
     * 根据 AGENTS.md 声明的工具名列表，从 ToolExecutor 中过滤出该 Worker 可用的工具。
     * 未知的工具名会被跳过（log 警告），确保不会因为 AGENTS.md 中写了不存在的工具而崩溃。
     */
    private List<ToolDefinition> resolveAllowedTools(List<String> toolsNeeded) {
        if (toolsNeeded == null || toolsNeeded.isEmpty()) {
            return List.of();
        }

        return toolsNeeded.stream()
                .map(name -> {
                    ToolDefinition def = toolExecutor.getDefinition(name);
                    if (def == null) {
                        log.warn("[WorkerEngine] Unknown tool declared in AGENTS.md: '{}', skipping", name);
                    }
                    return def;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // ============================================================
    // 文件操作
    // ============================================================

    private String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    private String readFileIfExists(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (IOException e) {
            log.warn("[WorkerEngine] Failed to read optional file: {}", path);
        }
        return null;
    }

    /**
     * 读取 output/ 目录中的所有结果文件，拼接为 Markdown。
     * 这些文件是 Worker 通过 file_write 工具写入的最终交付物。
     */
    private String readOutputFiles(Path outputDir) {
        if (!Files.exists(outputDir)) return "";

        try (var paths = Files.walk(outputDir, 2)) {
            return paths
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(p -> {
                        try {
                            String relativeName = outputDir.relativize(p).toString();
                            String content = Files.readString(p);
                            return "### " + relativeName + "\n\n" + content;
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .filter(s -> !s.isEmpty())
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");
        } catch (IOException e) {
            log.warn("[WorkerEngine] Failed to read output directory: {}", outputDir, e);
            return "";
        }
    }
}
