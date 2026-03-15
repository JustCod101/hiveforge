package com.hiveforge.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveforge.llm.LlmClient;
import com.hiveforge.llm.StreamCallback;
import com.hiveforge.repository.DnaTemplateRepository;
import com.hiveforge.repository.HiveTaskRepository;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.spawner.AgentDna;
import com.hiveforge.spawner.SpawnedWorker;
import com.hiveforge.spawner.WorkerSpawner;
import com.hiveforge.worker.WorkerEngine;
import com.hiveforge.worker.WorkerResult;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ManagerAgent 的单元测试类。
 * 覆盖了蜂群任务的完整生命周期：
 * Phase 1: 任务规划 (Planning)
 * Phase 2: 孕育 Worker (Spawning)
 * Phase 3: 按策略执行 (Executing)
 * Phase 4: 结果聚合 (Aggregating)
 * Phase 5: 用后即焚销毁目录 (Destroying)
 */
@ExtendWith(MockitoExtension.class)
class ManagerAgentTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private WorkerSpawner workerSpawner;

    @Mock
    private WorkerEngine workerEngine;

    @Mock
    private ResultAggregator resultAggregator;

    @Mock
    private HiveTaskRepository taskRepo;

    @Mock
    private WorkerAgentRepository workerAgentRepo;

    @Mock
    private DnaTemplateRepository templateRepo;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private StreamCallback streamCallback;

    @InjectMocks
    private ManagerAgent managerAgent;

    @TempDir
    Path tempDir;
    
    // 用于捕获状态变化历史（因为代码复用同一个 HiveTask 对象）
    private List<String> statusHistory;

    @BeforeEach
    void setUp() {
        // 利用反射将 @Value 配置项注入到 ManagerAgent
        ReflectionTestUtils.setField(managerAgent, "maxWorkersPerTask", 10);
        
        // 覆盖系统属性，以确保 destroyTaskDir 使用测试的临时目录而不是系统的 /tmp
        System.setProperty("hiveforge.hive-base-dir", tempDir.toString());
        
        // 设置状态捕获器，在每次 update 时记录状态
        statusHistory = new ArrayList<>();
        doAnswer(invocation -> {
            HiveTask task = invocation.getArgument(0);
            statusHistory.add(task.getStatus());
            return null;
        }).when(taskRepo).update(any(HiveTask.class));
    }

    /**
     * 测试场景 1：成功执行一个包含 2 个 Worker 的蜂群任务（并行策略）。
     * 验证了完整的生命周期事件和对相关服务的调用。
     */
    @Test
    void testExecuteHiveSuccess_Parallel() throws Exception {
        // ============================================================
        // 1. 准备 Mock 数据 (Arrange)
        // ============================================================
        String userQuery = "帮我调研 Apple 和 Microsoft 的最新财报并对比";
        
        // Mock DB 模板库返回空（此时会触发 LLM 从零生成）
        when(templateRepo.findAll()).thenReturn(Collections.emptyList());

        // Mock Phase 1: LLM 返回规划好的 JSON 字符串
        String fakeJsonResponse = "{\"workers\":[{\"name\":\"apple_analyst\"},{\"name\":\"ms_analyst\"}]}";
        when(llmClient.chat(anyString())).thenReturn(fakeJsonResponse);

        // Mock ObjectMapper 解析 JSON 为 TaskPlan 对象
        TaskPlan mockTaskPlan = new TaskPlan();
        mockTaskPlan.setTaskSummary("调研两家公司财报");
        mockTaskPlan.setExecutionStrategy("parallel"); // 指定并行策略
        
        WorkerPlan wp1 = new WorkerPlan();
        wp1.setName("apple_analyst");
        wp1.setToolsNeeded(List.of("web_search"));
        
        WorkerPlan wp2 = new WorkerPlan();
        wp2.setName("ms_analyst");
        wp2.setToolsNeeded(List.of("web_search"));
        
        mockTaskPlan.setWorkers(List.of(wp1, wp2));
        
        when(objectMapper.readValue(eq(fakeJsonResponse), eq(TaskPlan.class)))
                .thenReturn(mockTaskPlan);
        when(objectMapper.writeValueAsString(any())).thenReturn(fakeJsonResponse);

        // Mock Phase 2: 孕育 Worker (Spawning)
        // 在临时目录下为每个 Worker 创建虚拟的工作目录，用于后续 Phase 5 的销毁测试
        Path workerDir1 = tempDir.resolve("apple_analyst_123");
        Path workerDir2 = tempDir.resolve("ms_analyst_456");
        Files.createDirectories(workerDir1);
        Files.createDirectories(workerDir2);
        
        SpawnedWorker sw1 = new SpawnedWorker("w-123", "apple_analyst", workerDir1.toString(), new AgentDna("s", "a", "t"), List.of("web_search"));
        SpawnedWorker sw2 = new SpawnedWorker("w-456", "ms_analyst", workerDir2.toString(), new AgentDna("s", "a", "t"), List.of("web_search"));

        when(workerSpawner.spawn(anyString(), eq(wp1), eq(userQuery))).thenReturn(sw1);
        when(workerSpawner.spawn(anyString(), eq(wp2), eq(userQuery))).thenReturn(sw2);

        // Mock Phase 3: Worker 执行 (Executing)
        when(workerEngine.execute(sw1)).thenReturn(WorkerResult.success("apple_analyst", "Apple 财报良好"));
        when(workerEngine.execute(sw2)).thenReturn(WorkerResult.success("ms_analyst", "Microsoft 财报稳定"));

        // Mock Phase 4: 结果聚合 (Aggregating)
        when(resultAggregator.aggregate(eq(userQuery), eq(mockTaskPlan), anyList(), eq(streamCallback)))
                .thenReturn("# 最终对比报告\\nApple 和 MS 都很不错。");

        // ============================================================
        // 2. 执行核心逻辑 (Act)
        // ============================================================
        HiveResult result = managerAgent.executeHive(userQuery, streamCallback);

        // ============================================================
        // 3. 验证结果 (Assert)
        // ============================================================
        assertNotNull(result);
        assertNotNull(result.getTaskId());
        assertEquals("# 最终对比报告\\nApple 和 MS 都很不错。", result.getReport());
        assertEquals(2, result.getWorkerResults().size(), "应当有两个 Worker 执行结果");

        // 验证生命周期状态更新是否正确存入数据库
        verify(taskRepo, atLeastOnce()).save(any(HiveTask.class));
        
        // 验证状态变化历史（由 setUp 中的 doAnswer 捕获）
        assertEquals(6, statusHistory.size(), "应该有6次状态更新");
        assertEquals("PLANNING", statusHistory.get(0));
        assertEquals("PLANNING", statusHistory.get(1));
        assertEquals("SPAWNING", statusHistory.get(2));
        assertEquals("EXECUTING", statusHistory.get(3));
        assertEquals("AGGREGATING", statusHistory.get(4));
        assertEquals("COMPLETED", statusHistory.get(5));

        // 验证 SSE 实时推送事件是否都触发了
        verify(streamCallback).onEvent(eq("hive_start"), anyString());
        verify(streamCallback).onEvent(eq("planning"), anyString());
        verify(streamCallback).onEvent(eq("plan_ready"), anyString());
        verify(streamCallback).onEvent(eq("spawning"), anyString());
        verify(streamCallback, times(2)).onEvent(eq("worker_spawned"), anyString());
        verify(streamCallback).onEvent(eq("executing"), anyString());
        verify(streamCallback, times(2)).onEvent(eq("worker_done"), anyString());
        verify(streamCallback).onEvent(eq("aggregating"), anyString());
        verify(streamCallback).onEvent(eq("destroying"), anyString());
        verify(streamCallback, times(2)).onEvent(eq("worker_destroyed"), anyString());
        verify(streamCallback).onEvent(eq("hive_complete"), anyString());

        // 验证 Phase 5：用后即焚 (目录应被递归删除)
        assertFalse(Files.exists(workerDir1), "Worker 1 的工作目录应当被销毁");
        assertFalse(Files.exists(workerDir2), "Worker 2 的工作目录应当被销毁");
        verify(workerAgentRepo).updateStatus("w-123", "DESTROYED");
        verify(workerAgentRepo).updateStatus("w-456", "DESTROYED");
    }

    /**
     * 测试场景 2：DAG (有向无环图) 执行策略。
     * 验证当 Worker 之间存在依赖关系时，ManagerAgent 能正确按拓扑顺序调度执行。
     */
    @Test
    void testExecuteHiveDAG() throws Exception {
        String userQuery = "DAG 任务测试";

        // Mock LLM & JSON
        when(templateRepo.findAll()).thenReturn(Collections.emptyList());
        when(llmClient.chat(anyString())).thenReturn("{}");

        TaskPlan dagPlan = new TaskPlan();
        dagPlan.setExecutionStrategy("dag"); // 指定 DAG 策略

        // Worker A: 无依赖
        WorkerPlan wpA = new WorkerPlan();
        wpA.setName("workerA");
        wpA.setToolsNeeded(List.of("file_write"));

        // Worker B: 依赖 Worker A
        WorkerPlan wpB = new WorkerPlan();
        wpB.setName("workerB");
        wpB.setDependsOn(List.of("workerA"));
        wpB.setToolsNeeded(List.of("file_write"));

        dagPlan.setWorkers(List.of(wpA, wpB));

        when(objectMapper.readValue(anyString(), eq(TaskPlan.class))).thenReturn(dagPlan);
        
        // Mock Spawn
        SpawnedWorker swA = new SpawnedWorker("wA", "workerA", tempDir.resolve("A").toString(), new AgentDna("","",""), List.of());
        SpawnedWorker swB = new SpawnedWorker("wB", "workerB", tempDir.resolve("B").toString(), new AgentDna("","",""), List.of());
        
        when(workerSpawner.spawn(anyString(), eq(wpA), anyString())).thenReturn(swA);
        when(workerSpawner.spawn(anyString(), eq(wpB), anyString())).thenReturn(swB);

        // Mock Execute
        when(workerEngine.execute(swA)).thenReturn(WorkerResult.success("workerA", "Done A"));
        when(workerEngine.execute(swB)).thenReturn(WorkerResult.success("workerB", "Done B"));

        // 执行
        HiveResult result = managerAgent.executeHive(userQuery, streamCallback);

        // 断言验证
        assertTrue(result.getWorkerResults().stream().allMatch(WorkerResult::isSuccess));
        
        // 验证 DAG 专属事件的回调是否被触发，即按层级执行 (Layer 1 -> Layer 2)
        verify(streamCallback, atLeastOnce()).onEvent(eq("dag_layer"), anyString());
        verify(streamCallback, atLeastOnce()).onEvent(eq("dag_layer_done"), anyString());
    }

    /**
     * 测试场景 3：异常情况下的全局兜底。
     * 如果 LLM 解析或任务规划阶段抛出异常，整个蜂群任务应被标记为 FAILED。
     */
    @Test
    void testExecuteHiveFailure_LLMThrowsException() throws Exception {
        String userQuery = "必定失败的任务";
        
        when(templateRepo.findAll()).thenReturn(Collections.emptyList());
        
        // 模拟 LLM 或解析阶段抛出运行时异常
        when(llmClient.chat(anyString())).thenThrow(new RuntimeException("LLM Timeout"));

        // 执行 & 断言异常被包装并抛出
        Exception exception = assertThrows(RuntimeException.class, () -> {
            managerAgent.executeHive(userQuery, streamCallback);
        });

        assertTrue(exception.getMessage().contains("Hive execution failed"));
        
        // 验证数据库状态被更新为 FAILED（通过 setUp 中的 doAnswer 捕获的状态历史）
        assertTrue(statusHistory.contains("PLANNING"), "应该有 PLANNING 状态（设置初始状态）");
        assertTrue(statusHistory.contains("FAILED"), "应该有 FAILED 状态（异常后更新）");
        
        // 验证 SSE 推送了错误事件
        verify(streamCallback).onEvent(eq("hive_error"), contains("LLM Timeout"));
    }
}