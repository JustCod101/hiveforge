package com.hiveforge.spawner;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.manager.WorkerPlan;
import com.hiveforge.repository.DnaTemplateRepository;
import com.hiveforge.repository.DnaTemplateRepository.DnaTemplate;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Worker Spawner — Worker Agent DNA 生成器
 *
 * 核心流程:
 * 1. 创建临时工作目录 /tmp/hive/{taskId}/{workerName}_{uuid}/
 * 2. 查找匹配的 DNA 模板（优先 DB → 再查 templates/ 目录），或让 LLM 从零生成
 * 3. LLM 生成 SOUL.md + AGENTS.md + TASK.md（用 === 分隔）
 * 4. 解析并写入文件系统 → Worker 诞生
 * 5. DNA 快照持久化到 SQLite（即使目录被 rm -rf，DNA 仍可追溯）
 * 6. 创建 output/ 子目录供 Worker 写结果
 */
@Service
public class WorkerSpawner {

    private static final Logger log = LoggerFactory.getLogger(WorkerSpawner.class);

    private final LlmClient llmClient;
    private final DnaTemplateRepository templateRepo;
    private final WorkerAgentRepository workerRepo;
    private final ToolRegistry toolRegistry;

    @Value("${hiveforge.hive-base-dir:/tmp/hive}")
    private String hiveBaseDir;

    /**
     * DNA 生成 Prompt — 指导 LLM 为 Worker 编写三份 Markdown DNA 文件。
     * 使用 === SOUL.md === / === AGENTS.md === / === TASK.md === 作为分隔标记，
     * 解析时按此正则切割。
     */
    private static final String DNA_GENERATION_PROMPT = """
        你是一个 Agent 架构师。你需要为一个 Worker Agent 编写 DNA 文件。

        ## Worker 信息
        - 角色: %s
        - 可用工具: %s
        - 任务: %s
        - 用户上下文: %s

        ## 输出要求
        请分别生成三个 Markdown 文件的内容。用 === 分隔：

        === SOUL.md ===
        (定义 Worker 的人格、专业背景、行为准则、输出格式要求)

        === AGENTS.md ===
        (声明可用工具及使用规则，每个工具的适用场景)

        === TASK.md ===
        (明确本次任务目标、具体步骤、交付物、约束条件)

        要求:
        - SOUL.md 要赋予 Worker 鲜明的专业人格，包含身份、行为准则、输出格式三部分
        - AGENTS.md 只声明该 Worker 真正需要的工具，并说明每个工具的使用规则
        - TASK.md 要非常具体，包含可衡量的完成标准和明确的交付物路径
        - 每个文件都要以一级标题开头
        """;

    /**
     * TASK.md 单独生成 Prompt — 基于模板生成时，SOUL.md 和 AGENTS.md 来自模板，
     * 但 TASK.md 始终针对具体任务由 LLM 动态生成。
     */
    private static final String TASK_GENERATION_PROMPT = """
        你是一个任务规划专家。请为一个 Worker Agent 编写 TASK.md 文件。

        ## Worker 角色
        %s

        ## 具体任务
        %s

        ## 用户上下文
        %s

        ## 输出格式
        请直接输出 TASK.md 的 Markdown 内容（不要包含 === 分隔符），需包含：
        1. # 本次任务 — 一级标题
        2. ## 目标 — 清晰的任务目标
        3. ## 具体步骤 — 有序的操作步骤
        4. ## 交付物 — 明确写入 `./output/` 目录的文件名和格式
        5. ## 约束 — 工具调用次数限制、时间限制、数据缺失时的处理方式
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

    /**
     * 孕育一个 Worker Agent — 完整的 spawn 流程
     *
     * @param taskId      所属蜂群任务 ID
     * @param plan        Worker 规划（来自 Manager 的任务分解）
     * @param userContext 用户原始请求（作为上下文传递给 Worker）
     * @return 已孕育的 Worker，包含 ID、目录路径、DNA 内容
     */
    public SpawnedWorker spawn(String taskId, WorkerPlan plan, String userContext) {
        String workerId = UUID.randomUUID().toString().substring(0, 8);
        String workerName = plan.getName();

        // ===== Step 1: 创建临时工作目录 =====
        // 路径: /tmp/hive/{taskId}/{workerName}_{uuid8}/
        Path workerDir = Path.of(hiveBaseDir, taskId, workerName + "_" + workerId);
        createDirectory(workerDir);
        log.info("[Spawner] Created worker directory: {}", workerDir);

        // ===== Step 2: 生成 DNA（模板优先 → LLM 兜底） =====
        AgentDna dna = generateDna(plan, userContext);

        // ===== Step 3: 写入文件系统 → Agent 诞生！ =====
        // 目录存在 = Agent 存活，这是 "Markdown 即 Agent" 的核心语义
        writeFile(workerDir.resolve("SOUL.md"), dna.getSoulMd());
        writeFile(workerDir.resolve("AGENTS.md"), dna.getAgentsMd());
        writeFile(workerDir.resolve("TASK.md"), dna.getTaskMd());

        // 写入 USER.md（用户上下文画像，供 Worker 理解任务背景）
        if (userContext != null && !userContext.isEmpty()) {
            String userMd = "# 用户上下文\n\n" + userContext;
            writeFile(workerDir.resolve("USER.md"), userMd);
        }

        // ===== Step 4: 创建 output/ 子目录供 Worker 写结果 =====
        createDirectory(workerDir.resolve("output"));

        // ===== Step 5: DNA 快照持久化到 SQLite =====
        // 即使后续 rm -rf 销毁了工作目录，DNA 快照仍可从数据库追溯
        workerRepo.save(workerId, taskId, workerName, workerDir.toString(),
                dna.getSoulMd(), dna.getAgentsMd(), dna.getTaskMd());

        log.info("[Spawner] Worker born: name={}, id={}, dir={}", workerName, workerId, workerDir);

        return new SpawnedWorker(workerId, workerName, workerDir.toString(),
                dna, plan.getToolsNeeded());
    }

    /**
     * DNA 生成策略：
     * 1. 如果 WorkerPlan 指定了 template 名称 → 从 DB 查找模板
     * 2. 如果模板存在 → 用模板的 SOUL/AGENTS + LLM 生成 TASK.md
     * 3. 如果模板不存在或未指定 → LLM 从零生成全部三份文件
     */
    private AgentDna generateDna(WorkerPlan plan, String userContext) {
        // 尝试模板匹配
        if (plan.getTemplate() != null && !plan.getTemplate().isBlank()) {
            DnaTemplate template = templateRepo.findByName(plan.getTemplate());
            if (template != null) {
                log.info("[Spawner] Using DNA template: {}", plan.getTemplate());
                templateRepo.incrementUsageCount(plan.getTemplate());
                return generateFromTemplate(template, plan, userContext);
            }
            log.warn("[Spawner] Template '{}' not found, falling back to LLM generation",
                    plan.getTemplate());
        }

        // LLM 从零生成
        log.info("[Spawner] Generating DNA from scratch via LLM for worker: {}", plan.getName());
        return generateFromScratch(plan, userContext);
    }

    /**
     * 基于模板生成 DNA：
     * - SOUL.md 和 AGENTS.md 来自模板，支持 {{变量}} 占位符替换
     * - TASK.md 始终由 LLM 针对具体任务动态生成（保证任务描述的精确性）
     */
    private AgentDna generateFromTemplate(DnaTemplate template, WorkerPlan plan,
                                           String userContext) {
        // 模板变量替换
        String soul = template.soulTemplate()
                .replace("{{task}}", plan.getTaskDescription() != null ? plan.getTaskDescription() : "")
                .replace("{{context}}", userContext != null ? userContext : "")
                .replace("{{role}}", plan.getRole() != null ? plan.getRole() : "");

        String agents = template.agentsTemplate()
                .replace("{{tools}}", plan.getToolsNeeded() != null
                        ? String.join(", ", plan.getToolsNeeded()) : "");

        // TASK.md 由 LLM 动态生成
        String taskMd = generateTaskMd(plan, userContext);

        return new AgentDna(soul, agents, taskMd);
    }

    /**
     * LLM 从零生成全部三份 DNA 文件。
     * LLM 生成 Markdown 是其本能，几乎不会出格式错误。
     */
    private AgentDna generateFromScratch(WorkerPlan plan, String userContext) {
        // 构建工具描述列表
        String toolsDesc = "无特殊工具";
        if (plan.getToolsNeeded() != null && !plan.getToolsNeeded().isEmpty()) {
            toolsDesc = plan.getToolsNeeded().stream()
                    .map(name -> "- " + name + ": " + toolRegistry.getDescription(name))
                    .collect(Collectors.joining("\n"));
        }

        String prompt = String.format(DNA_GENERATION_PROMPT,
                plan.getRole() != null ? plan.getRole() : plan.getName(),
                toolsDesc,
                plan.getTaskDescription() != null ? plan.getTaskDescription() : "完成指定任务",
                userContext != null ? userContext : "无");

        String response = llmClient.chat(prompt);
        return parseDna(response);
    }

    /**
     * 单独生成 TASK.md — 模板模式下使用。
     * TASK.md 必须针对具体任务定制，不能直接用模板。
     */
    private String generateTaskMd(WorkerPlan plan, String userContext) {
        String prompt = String.format(TASK_GENERATION_PROMPT,
                plan.getRole() != null ? plan.getRole() : plan.getName(),
                plan.getTaskDescription() != null ? plan.getTaskDescription() : "完成指定任务",
                userContext != null ? userContext : "无");

        return llmClient.chat(prompt);
    }

    /**
     * 解析 LLM 输出的 DNA 内容。
     * 按 === SOUL.md === / === AGENTS.md === / === TASK.md === 分隔符切割。
     * 如果解析失败则使用默认值，确保不会因为 LLM 格式偏差而崩溃。
     */
    AgentDna parseDna(String response) {
        // 正则匹配 === XXX.md === 分隔符，兼容前后空白和大小写
        String[] parts = response.split("===\\s*(?i)(SOUL|AGENTS|TASK)\\.md\\s*===");

        String soul;
        String agents;
        String task;

        if (parts.length >= 4) {
            // 正常切割：parts[0]=前导文本, parts[1]=SOUL, parts[2]=AGENTS, parts[3]=TASK
            soul = parts[1].trim();
            agents = parts[2].trim();
            task = parts[3].trim();
        } else if (parts.length == 3) {
            soul = parts[1].trim();
            agents = parts[2].trim();
            task = "# 默认任务\n\n完成用户交代的任务，将结果写入 `./output/result.md`。";
            log.warn("[Spawner] DNA parse: only found 2 sections, using default TASK.md");
        } else {
            // 完全解析失败，将整个响应作为 SOUL.md，其余使用默认值
            log.warn("[Spawner] DNA parse failed, using response as SOUL.md. Response length: {}",
                    response.length());
            soul = response.trim();
            agents = "# 能力声明\n\n## 可用工具\n- file_write: 将结果写入文件\n- file_read: 读取文件内容";
            task = "# 默认任务\n\n完成用户交代的任务，将结果写入 `./output/result.md`。";
        }

        return new AgentDna(soul, agents, task);
    }

    // ===== 文件系统操作 =====

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
