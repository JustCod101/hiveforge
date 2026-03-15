package com.hiveforge.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveforge.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 工具执行器 — Worker 的 "手和脚"。
 *
 * 职责：
 * 1. 接收 LLM 的 tool_call（工具名 + JSON 参数）
 * 2. 从 ToolRegistry 查找工具实例
 * 3. 解析 JSON 参数
 * 4. 委托给具体的 Tool 实现执行
 * 5. 统一错误处理和日志
 *
 * 所有工具执行都限定在 Worker 工作目录内（沙盒隔离由各 Tool 自行实现）。
 */
@Service
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ToolExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据工具名获取 ToolDefinition（委托给 ToolRegistry）。
     * 供 WorkerEngine 过滤 Worker 可用的工具子集。
     */
    public ToolDefinition getDefinition(String toolName) {
        return toolRegistry.getDefinition(toolName);
    }

    /**
     * 在指定工作目录上下文中执行工具。
     *
     * @param toolName   工具名称（来自 LLM tool_call.function.name）
     * @param argsJson   JSON 格式参数（来自 LLM tool_call.function.arguments）
     * @param workingDir Worker 工作目录（沙盒根）
     * @return 工具执行结果
     */
    public ToolResult executeInContext(String toolName, String argsJson, String workingDir) {
        log.info("[ToolExecutor] tool='{}', workingDir='{}'", toolName, workingDir);
        long start = System.currentTimeMillis();

        // 1. 查找工具
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.warn("[ToolExecutor] Unknown tool: '{}'", toolName);
            return new ToolResult(false,
                    "Unknown tool: '" + toolName + "'. Available tools: " + toolRegistry.getAllToolNames());
        }

        try {
            // 2. 解析 JSON 参数
            JsonNode args;
            try {
                args = objectMapper.readTree(argsJson);
            } catch (Exception e) {
                log.warn("[ToolExecutor] Invalid JSON args for tool '{}': {}", toolName, argsJson);
                return new ToolResult(false,
                        "Invalid JSON arguments: " + e.getMessage());
            }

            // 3. 权限级别日志
            Tool.PermissionLevel level = tool.getPermissionLevel();
            if (level == Tool.PermissionLevel.DANGEROUS) {
                log.warn("[ToolExecutor] Executing DANGEROUS tool '{}' with args: {}",
                        toolName, argsJson.length() > 200 ? argsJson.substring(0, 200) + "..." : argsJson);
            }

            // 4. 委托执行
            ToolResult result = tool.execute(args, workingDir);

            long elapsed = System.currentTimeMillis() - start;
            log.info("[ToolExecutor] tool='{}' completed in {}ms, success={}, outputLen={}",
                    toolName, elapsed, result.isSuccess(),
                    result.getOutput() != null ? result.getOutput().length() : 0);

            return result;

        } catch (SandboxGuard.SandboxViolationException e) {
            log.warn("[ToolExecutor] Sandbox violation in tool '{}': {}", toolName, e.getMessage());
            return new ToolResult(false, "Security violation: " + e.getMessage());

        } catch (Exception e) {
            log.error("[ToolExecutor] Unexpected error in tool '{}'", toolName, e);
            return new ToolResult(false, "Tool execution error: " + e.getMessage());
        }
    }
}
