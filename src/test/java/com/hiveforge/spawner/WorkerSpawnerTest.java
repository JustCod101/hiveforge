package com.hiveforge.spawner;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.manager.WorkerPlan;
import com.hiveforge.repository.DnaTemplateRepository;
import com.hiveforge.repository.DnaTemplateRepository.DnaTemplate;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkerSpawner 的单元测试类。
 * 覆盖了基于模板生成 DNA、从零生成 DNA、解析 LLM 输出以及文件目录创建等核心逻辑。
 */
@ExtendWith(MockitoExtension.class)
class WorkerSpawnerTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private DnaTemplateRepository templateRepo;

    @Mock
    private WorkerAgentRepository workerRepo;

    @Mock
    private ToolRegistry toolRegistry;

    @InjectMocks
    private WorkerSpawner workerSpawner;

    /**
     * 使用 JUnit 5 的 @TempDir 注解创建一个临时目录，
     * 用于测试 WorkerSpawner 在文件系统上的操作，测试结束后会自动清理。
     */
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 利用反射将配置项注入到 WorkerSpawner 中，替代 Spring 的 @Value 注入
        ReflectionTestUtils.setField(workerSpawner, "hiveBaseDir", tempDir.toString());
    }

    /**
     * 测试场景 1：使用数据库中已有的 DNA 模板成功孕育 Worker
     */
    @Test
    void testSpawnWithExistingTemplate() throws Exception {
        // 1. 准备 Mock 数据 (Arrange)
        String taskId = "task-123";
        String userContext = "分析财报数据";

        WorkerPlan plan = new WorkerPlan();
        plan.setName("financial_analyst");
        plan.setRole("财务分析师");
        plan.setTaskDescription("分析 Apple 财报");
        plan.setToolsNeeded(List.of("web_search", "calculate"));
        plan.setTemplate("finance_template"); // 指定使用模板

        // 模拟数据库中存在该模板
        DnaTemplate mockTemplate = new DnaTemplate(1, "finance_template", "ANALYSIS",
                "# SOUL\\n角色: {{role}}", "# AGENTS\\n工具: {{tools}}", "财务模板", 0, 0.0);
        when(templateRepo.findByName("finance_template")).thenReturn(mockTemplate);

        // 模拟 LLM 动态生成 TASK.md
        when(llmClient.chat(anyString())).thenReturn("# TASK\\n具体任务内容...");

        // 2. 执行核心逻辑 (Act)
        SpawnedWorker worker = workerSpawner.spawn(taskId, plan, userContext);

        // 3. 验证结果 (Assert)
        assertNotNull(worker);
        assertEquals("financial_analyst", worker.getName());
        assertTrue(worker.getDir().startsWith(tempDir.toString() + "/" + taskId + "/financial_analyst_"));

        // 验证文件是否成功写入临时工作目录
        Path workerDirPath = Path.of(worker.getDir());
        assertTrue(Files.exists(workerDirPath), "Worker 工作目录应当被创建");
        assertTrue(Files.exists(workerDirPath.resolve("SOUL.md")), "SOUL.md 应当被写入");
        assertTrue(Files.exists(workerDirPath.resolve("AGENTS.md")), "AGENTS.md 应当被写入");
        assertTrue(Files.exists(workerDirPath.resolve("TASK.md")), "TASK.md 应当被写入");
        assertTrue(Files.exists(workerDirPath.resolve("USER.md")), "USER.md 应当被写入");
        assertTrue(Files.exists(workerDirPath.resolve("output")), "output/ 子目录应当被创建");

        // 验证文件内容是否正确替换了模板占位符
        String soulContent = Files.readString(workerDirPath.resolve("SOUL.md"));
        assertTrue(soulContent.contains("财务分析师"), "SOUL.md 中应当替换 {{role}} 变量");

        String agentsContent = Files.readString(workerDirPath.resolve("AGENTS.md"));
        assertTrue(agentsContent.contains("web_search, calculate"), "AGENTS.md 中应当包含工具列表");

        // 验证依赖服务被正确调用
        verify(templateRepo, times(1)).incrementUsageCount("finance_template");
        verify(workerRepo, times(1)).save(
                eq(worker.getId()), eq(taskId), eq(worker.getName()), eq(worker.getDir()),
                anyString(), anyString(), anyString());
    }

    /**
     * 测试场景 2：模板未找到，降级回退到让 LLM 从零完全生成
     */
    @Test
    void testSpawnFromScratchWhenTemplateNotFound() throws Exception {
        String taskId = "task-456";
        WorkerPlan plan = new WorkerPlan();
        plan.setName("researcher");
        plan.setRole("研究员");
        plan.setTemplate("missing_template"); // 指定了一个不存在的模板
        plan.setToolsNeeded(List.of("web_search"));

        // 模拟模板不存在
        when(templateRepo.findByName("missing_template")).thenReturn(null);

        // 模拟 ToolRegistry
        when(toolRegistry.getDescription("web_search")).thenReturn("用于搜索互联网信息");

        // 模拟 LLM 从零生成的完整输出（注意包含 === 分隔符）
        String llmResponse = """
            === SOUL.md ===
            # 你是一个研究员
            === AGENTS.md ===
            # 工具列表
            === TASK.md ===
            # 本次任务
            """;
        when(llmClient.chat(anyString())).thenReturn(llmResponse);

        SpawnedWorker worker = workerSpawner.spawn(taskId, plan, null);

        assertNotNull(worker);

        Path workerDirPath = Path.of(worker.getDir());
        assertTrue(Files.exists(workerDirPath.resolve("SOUL.md")));
        assertFalse(Files.exists(workerDirPath.resolve("USER.md")), "没有 User Context 时不应生成 USER.md");

        String soulContent = Files.readString(workerDirPath.resolve("SOUL.md"));
        assertEquals("# 你是一个研究员", soulContent.trim());
    }

    /**
     * 测试场景 3：验证对 LLM 正常输出的解析能力（包含所有三个分隔符）
     */
    @Test
    void testParseDnaNormal() {
        String response = """
            一些前言废话
            === SOUL.md ===
            # 灵魂
            === AGENTS.md ===
            # 工具
            === TASK.md ===
            # 任务
            一些后缀废话
            """;

        AgentDna dna = workerSpawner.parseDna(response);

        assertEquals("# 灵魂", dna.getSoulMd());
        assertEquals("# 工具", dna.getAgentsMd());
        assertTrue(dna.getTaskMd().startsWith("# 任务"));
    }

    /**
     * 测试场景 4：验证对 LLM 输出缺失 TASK.md 分隔符的容错处理
     */
    @Test
    void testParseDnaMissingTask() {
        String response = """
            === SOUL.md ===
            # 灵魂
            === AGENTS.md ===
            # 工具
            """;

        AgentDna dna = workerSpawner.parseDna(response);

        assertEquals("# 灵魂", dna.getSoulMd());
        assertEquals("# 工具", dna.getAgentsMd());
        assertTrue(dna.getTaskMd().contains("默认任务"), "缺失 TASK 时应降级为默认任务描述");
    }

    /**
     * 测试场景 5：验证 LLM 完全未按格式输出时的极端容错处理
     */
    @Test
    void testParseDnaCompleteFailure() {
        String response = "这是 LLM 乱说的一句话，没有任何分隔符。";

        AgentDna dna = workerSpawner.parseDna(response);

        assertEquals(response, dna.getSoulMd(), "解析完全失败时，应将整段回答作为 SOUL.md 保留");
        assertTrue(dna.getAgentsMd().contains("file_write"));
        assertTrue(dna.getTaskMd().contains("默认任务"));
    }
}