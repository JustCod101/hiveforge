package com.hiveforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveforge.llm.ChatResponse;
import com.hiveforge.llm.LlmClient;
import com.hiveforge.llm.StreamCallback;
import com.hiveforge.manager.HiveResult;
import com.hiveforge.manager.HiveTask;
import com.hiveforge.manager.ManagerAgent;
import com.hiveforge.repository.HiveTaskRepository;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 整个系统的端到端集成测试 (System Integration Test)。
 *
 * 验证：
 * 1. Spring Boot 上下文是否能够成功启动。
 * 2. 数据库 Schema（SQLite）是否正确初始化。
 * 3. ToolRegistry 是否成功将工具同步到数据库。
 * 4. ManagerAgent 统筹的完整生命周期（规划、生成、执行、聚合、销毁）是否与数据库交互正常。
 *
 * 注意：使用 MockBean 拦截真实的 LLM API 调用，保持测试的稳定性和快速执行。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/hiveforge_system_test.db",
        "hiveforge.hive-base-dir=target/test-hive-dir"
})
public class SystemIntegrationTest {

    @Autowired
    private ManagerAgent managerAgent;

    @Autowired
    private HiveTaskRepository taskRepo;

    @Autowired
    private WorkerAgentRepository workerRepo;

    @Autowired
    private ToolRegistry toolRegistry;

    @MockBean
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        // ====================================================================
        // Mock LlmClient 各种场景的返回值，模拟一个完整的蜂群工作流
        // ====================================================================

        // 1. Mock chat() - 用于 ManagerAgent 规划、聚合 以及 WorkerSpawner 生成 DNA
        when(llmClient.chat(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            
            // 场景 A: ManagerAgent 拆解任务
            if (prompt.contains("分析用户的复杂任务") || prompt.contains("严格 JSON")) {
                return """
                    {
                      "taskSummary": "System Integration Test Task",
                      "executionStrategy": "parallel",
                      "workers": [
                        {
                          "name": "integration_tester",
                          "role": "QA Engineer",
                          "taskDescription": "Run system validation",
                          "toolsNeeded": ["calculate"]
                        }
                      ]
                    }
                    """;
            }
            
            // 场景 B: ManagerAgent 最终聚合报告
            if (prompt.contains("最终报告") || prompt.contains("如何将各 Worker 结果合并为最终报告") && prompt.contains("==== 所有 Worker 执行结果 ====")) {
                return "# System Test Report\nAll tests passed successfully.";
            }
            
            // 场景 C: WorkerSpawner 生成 DNA
            if (prompt.contains("Agent 架构师") || prompt.contains("为 Worker Agent 编写 TASK.md 文件")) {
                return "=== SOUL.md ===\n# Role\nQA Engineer\n" +
                       "=== AGENTS.md ===\n# Tools\ncalculate\n" +
                       "=== TASK.md ===\n# Task\nRun system validation";
            }
            
            return "Default response";
        });

        // 2. Mock chatWithWorkerModel() - 用于 WorkerEngine 实际执行
        when(llmClient.chatWithWorkerModel(anyList(), anyList())).thenAnswer(invocation -> {
            // 直接返回最终结果，不触发 Tool Call 循环，简化测试
            return new ChatResponse("System validation completed.", null, "stop");
        });
    }

    @Test
    void testSystemFullLifecycle() {
        // 1. 验证基础服务是否正确启动并初始化
        assertNotNull(managerAgent, "ManagerAgent should be loaded");
        assertNotNull(taskRepo, "HiveTaskRepository should be loaded");
        
        // 2. 验证工具注册和数据库初始化
        assertTrue(toolRegistry.isAvailable("calculate"), "Builtin tools should be registered in DB");
        assertTrue(toolRegistry.isAvailable("web_search"));
        
        // 3. 执行端到端任务
        String userQuery = "Run a system integration test";
        HiveResult result = managerAgent.executeHive(userQuery, new StreamCallback() {
            @Override
            public void onEvent(String type, String message) {
                // 可以验证 SSE 推送，但这里主要验证系统行为
            }
        });

        // 4. 验证执行结果
        assertNotNull(result);
        assertNotNull(result.getTaskId());
        assertEquals("# System Test Report\nAll tests passed successfully.", result.getReport());
        assertEquals(1, result.getWorkerResults().size(), "Should have exactly 1 worker");
        assertTrue(result.getWorkerResults().get(0).isSuccess());

        // 5. 验证数据库持久化状态 (hive_task)
        HiveTask savedTask = taskRepo.findById(result.getTaskId());
        assertNotNull(savedTask);
        assertEquals("COMPLETED", savedTask.getStatus(), "Task should be marked as COMPLETED");
        assertEquals(1, savedTask.getWorkerCount());
        assertNotNull(savedTask.getFinalReport());

        // 6. 验证数据库持久化状态 (worker_agent)
        List<Map<String, Object>> workers = workerRepo.findByTaskId(result.getTaskId());
        assertEquals(1, workers.size());
        assertEquals("DESTROYED", workers.get(0).get("status"), "Worker dir should be destroyed");
        assertEquals("integration_tester", workers.get(0).get("worker_name"));
        assertNotNull(workers.get(0).get("soul_md"));
        assertNotNull(workers.get(0).get("agents_md"));
        assertNotNull(workers.get(0).get("task_md"));
    }
}
