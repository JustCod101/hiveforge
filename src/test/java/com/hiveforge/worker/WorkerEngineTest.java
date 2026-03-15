package com.hiveforge.worker;

import com.hiveforge.llm.ChatResponse;
import com.hiveforge.llm.LlmClient;
import com.hiveforge.llm.ToolCall;
import com.hiveforge.llm.ToolDefinition;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.repository.WorkerTraceRepository;
import com.hiveforge.spawner.AgentDna;
import com.hiveforge.spawner.SpawnedWorker;
import com.hiveforge.tool.ToolExecutor;
import com.hiveforge.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkerEngine 的单元测试类。
 * 测试 "灵魂载入" (读取 DNA 文件)、工具权限过滤、ReAct 循环 (LLM 交互与工具调用)、
 * 最终输出文件读取以及 Trace 记录等核心逻辑。
 */
@ExtendWith(MockitoExtension.class)
class WorkerEngineTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private WorkerTraceRepository traceRepo;

    @Mock
    private WorkerAgentRepository workerRepo;

    @InjectMocks
    private WorkerEngine workerEngine;

    @TempDir
    Path tempDir;

    private SpawnedWorker testWorker;
    private Path workerDir;

    @BeforeEach
    void setUp() throws IOException {
        // 创建测试用的 Worker 工作目录
        workerDir = tempDir.resolve("test_worker_123");
        Files.createDirectories(workerDir);

        // 初始化基础 DNA 文件
        Files.writeString(workerDir.resolve("SOUL.md"), "# SOUL\\n测试灵魂");
        Files.writeString(workerDir.resolve("AGENTS.md"), "# AGENTS\\n可用工具: web_search");
        Files.writeString(workerDir.resolve("TASK.md"), "# TASK\\n测试任务");

        // 创建 output 目录
        Files.createDirectories(workerDir.resolve("output"));

        // 初始化 SpawnedWorker 对象
        AgentDna mockDna = new AgentDna("soul", "agents", "task");
        testWorker = new SpawnedWorker(
                "worker-123",
                "test_analyst",
                workerDir.toString(),
                mockDna,
                List.of("web_search") // 该 Worker 允许使用的工具
        );
    }

    /**
     * 测试场景 1：完整的 ReAct 成功循环
     * 模拟一轮包含 Tool Call 的 LLM 返回，然后第二轮 LLM 返回任务完成。
     */
    @Test
    void testExecuteSuccessWithToolCalls() throws IOException {
        // --- 准备 Mock 数据 (Arrange) ---

        // 1. Mock 工具定义：Worker 允许使用 web_search，ToolExecutor 返回对应的定义
        ToolDefinition mockWebSearchDef = new ToolDefinition("web_search", "搜索", "{}");
        when(toolExecutor.getDefinition("web_search")).thenReturn(mockWebSearchDef);

        // 2. Mock 第一次 LLM 调用：返回一段 Thought 并要求调用 web_search 工具
        ToolCall mockToolCall = new ToolCall("call_1", "web_search", "{\"query\":\"apple\"}");
        ChatResponse round1Response = new ChatResponse("我需要搜索 Apple 的信息", List.of(mockToolCall), "tool_calls");

        // 3. Mock 第二次 LLM 调用：未返回工具调用，表示任务完成
        ChatResponse round2Response = new ChatResponse("任务完成，苹果公司是一家科技公司。", null, "stop");

        // 模拟 LLM 客户端的行为，按顺序返回
        when(llmClient.chatWithWorkerModel(anyList(), anyList()))
                .thenReturn(round1Response)
                .thenReturn(round2Response);

        // 4. Mock 工具执行：当 web_search 被调用时，返回成功的结果
        when(toolExecutor.executeInContext(eq("web_search"), anyString(), eq(workerDir.toString())))
                .thenReturn(new ToolResult(true, "搜索结果：Apple 是科技公司"));

        // 5. 在 output 目录中放置一个虚拟的文件，模拟 Worker 最终产出的结果
        Files.writeString(workerDir.resolve("output").resolve("report.md"), "最终的报告内容");

        // --- 执行测试 (Act) ---
        WorkerResult result = workerEngine.execute(testWorker);

        // --- 验证结果 (Assert) ---
        assertTrue(result.isSuccess(), "Worker 执行应当成功");
        assertEquals("test_analyst", result.getWorkerName());

        // 验证输出中包含了 LLM 的思考内容
        assertTrue(result.getOutput().contains("我需要搜索 Apple 的信息"));
        assertTrue(result.getOutput().contains("任务完成，苹果公司是一家科技公司。"));

        // 验证输出中包含了从 output/ 目录读取的文件内容
        assertTrue(result.getOutput().contains("report.md"));
        assertTrue(result.getOutput().contains("最终的报告内容"));

        // 验证状态更新被调用：INITIALIZING -> EXECUTING -> COMPLETED
        verify(workerRepo).updateStatus("worker-123", "INITIALIZING");
        verify(workerRepo).updateStatus("worker-123", "EXECUTING");
        verify(workerRepo).updateStartedAt(eq("worker-123"), anyString());
        // 验证更新完成状态 (包括了 tool calls 和 llm calls 统计: tool 1次, LLM 2次)
        verify(workerRepo).updateCompleted(eq("worker-123"), anyString(), eq(1), eq(2), anyLong());

        // 验证 Trace 记录是否按预期进行
        // Thought
        verify(traceRepo, atLeastOnce()).saveWithLatency(eq("worker-123"), anyInt(), eq("THOUGHT"), anyString(), isNull(), isNull(), isNull(), anyLong());
        // Action (Tool Call)
        verify(traceRepo, times(1)).save(eq("worker-123"), anyInt(), eq("ACTION"), anyString(), eq("web_search"), anyString(), isNull());
        // Observation (Tool Result)
        verify(traceRepo, times(1)).saveWithLatency(eq("worker-123"), anyInt(), eq("OBSERVATION"), eq("搜索结果：Apple 是科技公司"), eq("web_search"), anyString(), anyString(), anyLong());
        // Reflection (Task Completed)
        verify(traceRepo, times(1)).save(eq("worker-123"), anyInt(), eq("REFLECTION"), anyString(), isNull(), isNull(), isNull());
    }

    /**
     * 测试场景 2：不需要任何工具调用的快速完成
     * 如果 LLM 第一次就判断能直接完成任务（无 tool call），循环直接结束。
     */
    @Test
    void testExecuteQuickFinishNoToolCalls() {
        // 工具列表为空也能正常运行
        when(toolExecutor.getDefinition(anyString())).thenReturn(null);

        ChatResponse directFinishResponse = new ChatResponse("我已知晓答案，直接完成任务", null, "stop");
        when(llmClient.chatWithWorkerModel(anyList(), anyList())).thenReturn(directFinishResponse);

        WorkerResult result = workerEngine.execute(testWorker);

        assertTrue(result.isSuccess());
        verify(toolExecutor, never()).executeInContext(anyString(), anyString(), anyString());
        // 只进行过 1 次 LLM 调用，0 次工具调用
        verify(workerRepo).updateCompleted(eq("worker-123"), anyString(), eq(0), eq(1), anyLong());
    }

    /**
     * 测试场景 3：执行失败时的异常捕获与状态更新
     * 模拟读取 DNA 文件时发生 IOException，引发全局异常。
     */
    @Test
    void testExecuteFailureHandling() throws IOException {
        // 删除必需的 SOUL.md 以故意引发 IOException
        Files.delete(workerDir.resolve("SOUL.md"));

        WorkerResult result = workerEngine.execute(testWorker);

        assertFalse(result.isSuccess(), "Worker 执行应当失败");
        assertNotNull(result.getError());

        // 验证错误状态被更新
        verify(workerRepo).updateFailed(eq("worker-123"), anyString(), anyLong());
        // 不应走到后续步骤
        verify(workerRepo, never()).updateStatus("worker-123", "INITIALIZING");
    }

    /**
     * 测试场景 4：工具执行失败时的容错处理
     * 如果 ToolExecutor 返回失败，应当将错误信息作为 Observation 记录，且引擎不能崩溃。
     */
    @Test
    void testToolExecutionFailureHandling() {
        ToolDefinition mockDef = new ToolDefinition("web_search", "搜索", "{}");
        when(toolExecutor.getDefinition("web_search")).thenReturn(mockDef);

        ToolCall mockToolCall = new ToolCall("call_1", "web_search", "{\"query\":\"apple\"}");
        ChatResponse round1 = new ChatResponse("尝试搜索", List.of(mockToolCall), "tool_calls");
        ChatResponse round2 = new ChatResponse("搜索失败，放弃任务", null, "stop");

        when(llmClient.chatWithWorkerModel(anyList(), anyList()))
                .thenReturn(round1)
                .thenReturn(round2);

        // 模拟工具执行失败
        when(toolExecutor.executeInContext(eq("web_search"), anyString(), anyString()))
                .thenReturn(new ToolResult(false, "Network Timeout"));

        WorkerResult result = workerEngine.execute(testWorker);

        assertTrue(result.isSuccess(), "任务本身应该结束并返回 success（因为容错继续执行了）");
        
        // 验证是否将错误信息以 "[ERROR]" 为前缀记录到了 Observation
        verify(traceRepo).saveWithLatency(
                eq("worker-123"), 
                anyInt(), 
                eq("OBSERVATION"), 
                eq("[ERROR] Network Timeout"), 
                eq("web_search"), 
                anyString(), 
                anyString(), 
                anyLong());
    }

    /**
     * 测试场景 5：可选的 USER.md 读取
     * 确保当 USER.md 存在时，也能正确并入到后续处理。
     */
    @Test
    void testExecuteWithUserMd() throws IOException {
        Files.writeString(workerDir.resolve("USER.md"), "# 用户画像\\n资深架构师");

        when(llmClient.chatWithWorkerModel(anyList(), anyList()))
                .thenReturn(new ChatResponse("直接完成", null, "stop"));

        WorkerResult result = workerEngine.execute(testWorker);

        assertTrue(result.isSuccess());
        // 如果 USER.md 读取成功，说明没有抛出异常导致流程中断
    }
}