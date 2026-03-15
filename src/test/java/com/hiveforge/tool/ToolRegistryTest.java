package com.hiveforge.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ToolRegistry 工具注册表测试类。
 * 验证内置工具自动注册、自定义工具动态注册以及数据库状态同步等逻辑。
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        // 由于依赖了 Mock，我们不要在这里直接调 init()，而是通过具体测试方法去控制
    }

    /**
     * 测试场景 1：初始化时自动注册内置工具并同步到数据库
     */
    @Test
    void testInitRegistersBuiltinTools() {
        // 调用初始化方法
        toolRegistry.init();

        // 验证内置工具是否都已经被加载
        List<String> registeredTools = toolRegistry.getAllToolNames();
        assertTrue(registeredTools.contains("file_read"));
        assertTrue(registeredTools.contains("file_write"));
        assertTrue(registeredTools.contains("file_list"));
        assertTrue(registeredTools.contains("web_search"));
        assertTrue(registeredTools.contains("http_call"));
        assertTrue(registeredTools.contains("shell_exec"));
        assertTrue(registeredTools.contains("calculate"));

        // 验证同步数据库是否被调用（至少 7 个内置工具被注册，所以至少被调用 7 次 upsert）
        verify(jdbcTemplate, atLeast(7)).update(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 测试场景 2：数据库同步失败时的降级处理
     * 即便 DB 挂了或出错，内存中的注册不应该被中断。
     */
    @Test
    void testSyncToDatabaseFailureDoesNotCrashRegistry() {
        // 模拟数据库更新时抛出异常
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("DB Connection Refused"));

        // 执行注册
        assertDoesNotThrow(() -> toolRegistry.init());

        // 验证即便 DB 挂了，工具也成功保存在了内存 Map 中
        assertTrue(toolRegistry.isAvailable("file_read"));
    }

    /**
     * 测试场景 3：动态注册自定义扩展工具
     */
    @Test
    void testRegisterCustomTool() {
        // 创建一个简单的 Mock 工具
        Tool customTool = mock(Tool.class);
        when(customTool.getName()).thenReturn("custom_tool");
        when(customTool.getDescription()).thenReturn("A custom tool");
        when(customTool.getParameterSchema()).thenReturn("{}");
        when(customTool.getPermissionLevel()).thenReturn(Tool.PermissionLevel.SAFE);

        toolRegistry.register(customTool);

        // 验证注册成功
        assertTrue(toolRegistry.isAvailable("custom_tool"));
        assertEquals(customTool, toolRegistry.getTool("custom_tool"));
        
        // 验证也向数据库做了同步
        verify(jdbcTemplate, times(1)).update(
                anyString(),
                eq("custom_tool"),
                eq("A custom tool"),
                eq("{}"),
                eq("SAFE")
        );
    }

    /**
     * 测试场景 4：获取缺失工具的描述时，回退到 DB 查询
     */
    @Test
    void testGetDescriptionFallbackToDatabase() {
        // 内存中没有名为 "remote_db_tool" 的工具
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("remote_db_tool")))
                .thenReturn("A tool loaded from remote database");

        String desc = toolRegistry.getDescription("remote_db_tool");

        assertEquals("A tool loaded from remote database", desc);
    }

    /**
     * 测试场景 5：从内存与数据库中禁用工具
     */
    @Test
    void testDisableTool() {
        toolRegistry.init();
        assertTrue(toolRegistry.isAvailable("file_read"));

        toolRegistry.disable("file_read");

        // 内存中已被移除
        assertFalse(toolRegistry.isAvailable("file_read"));
        assertNull(toolRegistry.getTool("file_read"));
        
        // 验证执行了数据库更新将其 enabled 设为 0
        verify(jdbcTemplate).update(anyString(), eq("file_read"));
    }
}