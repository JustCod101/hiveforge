package com.hiveforge.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ToolExecutor 工具执行器测试类。
 * 验证对 ToolRegistry 的查询、JSON 参数解析、工具分发以及沙盒异常保护。
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private Tool mockTool;

    private ObjectMapper objectMapper = new ObjectMapper();

    // 手动实例化 ToolExecutor，因为我们需要注入真实的 ObjectMapper
    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        toolExecutor = new ToolExecutor(toolRegistry, objectMapper);
    }

    /**
     * 测试场景 1：成功执行一个工具
     */
    @Test
    void testExecuteInContextSuccess() {
        // Arrange
        String toolName = "mock_tool";
        String argsJson = "{\"param\": \"value\"}";
        String workingDir = "/tmp/sandbox";

        when(toolRegistry.getTool(toolName)).thenReturn(mockTool);
        when(mockTool.getPermissionLevel()).thenReturn(Tool.PermissionLevel.SAFE);
        
        ToolResult expectedResult = new ToolResult(true, "success output");
        when(mockTool.execute(any(JsonNode.class), eq(workingDir))).thenReturn(expectedResult);

        // Act
        ToolResult actualResult = toolExecutor.executeInContext(toolName, argsJson, workingDir);

        // Assert
        assertTrue(actualResult.isSuccess());
        assertEquals("success output", actualResult.getOutput());
        verify(mockTool, times(1)).execute(any(JsonNode.class), eq(workingDir));
    }

    /**
     * 测试场景 2：尝试调用一个未知的/不存在的工具
     */
    @Test
    void testExecuteUnknownTool() {
        String toolName = "unknown_tool";
        when(toolRegistry.getTool(toolName)).thenReturn(null);
        when(toolRegistry.getAllToolNames()).thenReturn(java.util.List.of("toolA", "toolB"));

        ToolResult result = toolExecutor.executeInContext(toolName, "{}", "/tmp");

        assertFalse(result.isSuccess(), "调用未知工具应当失败");
        assertTrue(result.getOutput().contains("Unknown tool: 'unknown_tool'"));
        assertTrue(result.getOutput().contains("toolA")); // 错误信息应提示可用工具
    }

    /**
     * 测试场景 3：非法的 JSON 参数格式防御
     */
    @Test
    void testExecuteInvalidJsonArgs() {
        String toolName = "mock_tool";
        String badJson = "{ invalid json "; // 格式错误

        when(toolRegistry.getTool(toolName)).thenReturn(mockTool);

        ToolResult result = toolExecutor.executeInContext(toolName, badJson, "/tmp");

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Invalid JSON arguments"));
        verify(mockTool, never()).execute(any(), any());
    }

    /**
     * 测试场景 4：执行工具时触发了沙盒越权保护
     */
    @Test
    void testExecuteSandboxViolationCaught() {
        String toolName = "mock_tool";
        
        when(toolRegistry.getTool(toolName)).thenReturn(mockTool);
        when(mockTool.getPermissionLevel()).thenReturn(Tool.PermissionLevel.MODERATE);
        
        // 模拟工具执行时抛出沙盒异常
        when(mockTool.execute(any(), any())).thenThrow(
                new SandboxGuard.SandboxViolationException("Path escape denied")
        );

        ToolResult result = toolExecutor.executeInContext(toolName, "{}", "/tmp");

        assertFalse(result.isSuccess(), "触发沙盒越权时应返回失败结果");
        assertTrue(result.getOutput().contains("Security violation: Path escape denied"));
    }

    /**
     * 测试场景 5：工具执行期间发生其他未知异常的兜底捕获，防止引擎崩溃
     */
    @Test
    void testExecuteUnexpectedExceptionCaught() {
        String toolName = "mock_tool";
        
        when(toolRegistry.getTool(toolName)).thenReturn(mockTool);
        when(mockTool.getPermissionLevel()).thenReturn(Tool.PermissionLevel.SAFE);
        
        // 模拟空指针异常等不可预见的错误
        when(mockTool.execute(any(), any())).thenThrow(new NullPointerException("NPE test"));

        ToolResult result = toolExecutor.executeInContext(toolName, "{}", "/tmp");

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Tool execution error"));
    }
}