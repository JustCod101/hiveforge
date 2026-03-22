package com.hiveforge.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveforge.llm.ToolDefinition;
import com.hiveforge.tool.builtin.*;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 — 管理所有内置和自定义工具。
 *
 * 职责：
 * 1. 内置工具注册 — 启动时自动注册所有 builtin 工具
 * 2. DB 同步 — 工具元数据持久化到 tool_registry 表
 * 3. 权限管理 — 每个工具有 SAFE / MODERATE / DANGEROUS 三级权限
 * 4. 工具查找 — 按名称获取工具实例或定义
 * 5. 工具描述 — 供 WorkerSpawner 在 DNA 生成时使用
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Value("${tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${serpapi.api-key:}")
    private String serpApiKey;

    /** name → Tool 实例 */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, OkHttpClient httpClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 启动时注册所有内置工具并同步到 DB。
     * 使用 CommandLineRunner 确保在数据库初始化之后执行。
     */
    @Bean
    @Order(3) // 在 DnaTemplateInitializer (Order=2) 之后执行
    public CommandLineRunner initTools() {
        return args -> {
            // 注册内置工具
            registerBuiltin(new FileReadTool());
            registerBuiltin(new FileWriteTool());
            registerBuiltin(new FileListTool());
            registerBuiltin(new WebSearchTool(httpClient, objectMapper, tavilyApiKey, serpApiKey));
            registerBuiltin(new HttpCallTool(httpClient));
            registerBuiltin(new ShellExecuteTool());
            registerBuiltin(new CalculateTool());

            log.info("[ToolRegistry] Registered {} tools: {}", tools.size(), tools.keySet());
        };
    }

    /**
     * 注册内置工具：加入内存 Map 并同步到 DB。
     */
    private void registerBuiltin(Tool tool) {
        tools.put(tool.getName(), tool);
        syncToDatabase(tool);
    }

    /**
     * 同步工具元数据到 tool_registry 表。
     * 使用 upsert 保证幂等。
     */
    private void syncToDatabase(Tool tool) {
        try {
            jdbcTemplate.update("""
                INSERT INTO tool_registry (tool_name, description, parameter_schema, permission_level, enabled)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(tool_name) DO UPDATE SET
                    description = excluded.description,
                    parameter_schema = excluded.parameter_schema,
                    permission_level = excluded.permission_level,
                    enabled = 1
                """,
                    tool.getName(),
                    tool.getDescription(),
                    tool.getParameterSchema(),
                    tool.getPermissionLevel().name());
        } catch (Exception e) {
            log.warn("[ToolRegistry] Failed to sync tool '{}' to DB: {}", tool.getName(), e.getMessage());
        }
    }

    // ============================================================
    // 查询接口
    // ============================================================

    /**
     * 根据名称获取工具实例。
     * @return Tool 实例或 null（未知工具）
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 根据名称获取工具的 LLM ToolDefinition。
     * 供 WorkerEngine 注册到 LLM 的 function calling tools[] 中。
     * @return ToolDefinition 或 null（未知工具）
     */
    public ToolDefinition getDefinition(String name) {
        Tool tool = tools.get(name);
        return tool != null ? tool.toDefinition() : null;
    }

    /**
     * 获取工具描述。
     * 供 WorkerSpawner 在 DNA 生成 Prompt 中告诉 LLM 有哪些工具可用。
     */
    public String getDescription(String name) {
        Tool tool = tools.get(name);
        if (tool != null) {
            return tool.getDescription();
        }
        // 回退到 DB 查询（兼容自定义工具）
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT description FROM tool_registry WHERE tool_name = ? AND enabled = 1",
                    String.class, name);
        } catch (Exception e) {
            return name + ": unknown tool";
        }
    }

    /**
     * 获取工具权限级别。
     */
    public Tool.PermissionLevel getPermissionLevel(String name) {
        Tool tool = tools.get(name);
        return tool != null ? tool.getPermissionLevel() : null;
    }

    /**
     * 检查工具是否已注册且启用。
     */
    public boolean isAvailable(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取所有已注册的工具名称列表。
     */
    public List<String> getAllToolNames() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 获取所有工具的只读视图。
     */
    public Map<String, Tool> getAllTools() {
        return Collections.unmodifiableMap(tools);
    }

    /**
     * 动态注册自定义工具（运行时扩展）。
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        syncToDatabase(tool);
        log.info("[ToolRegistry] Dynamically registered tool: {}", tool.getName());
    }

    /**
     * 禁用一个工具（从内存移除，DB 标记 enabled=0）。
     */
    public void disable(String name) {
        tools.remove(name);
        try {
            jdbcTemplate.update(
                    "UPDATE tool_registry SET enabled = 0 WHERE tool_name = ?", name);
        } catch (Exception e) {
            log.warn("[ToolRegistry] Failed to disable tool '{}' in DB", name, e);
        }
        log.info("[ToolRegistry] Disabled tool: {}", name);
    }
}
