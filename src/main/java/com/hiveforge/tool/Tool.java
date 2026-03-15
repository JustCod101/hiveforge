package com.hiveforge.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiveforge.llm.ToolDefinition;

/**
 * 工具接口 — 所有内置工具和自定义工具必须实现此接口。
 *
 * 每个 Tool 职责单一：
 * - 声明自己的名称、描述、参数 Schema、权限级别
 * - 在指定的工作目录上下文中执行操作
 * - 返回结构化的 ToolResult
 */
public interface Tool {

    /** 工具名称（唯一标识，如 "file_read"） */
    String getName();

    /** 工具描述（告诉 LLM 这个工具能做什么） */
    String getDescription();

    /** 参数 JSON Schema（OpenAI function calling 格式） */
    String getParameterSchema();

    /**
     * 权限级别：
     * - SAFE: 只读操作，无副作用（如 file_read, calculate）
     * - MODERATE: 有副作用但限定在沙盒内（如 file_write, http_call）
     * - DANGEROUS: 可能影响系统（如 shell_exec），需要额外审计
     */
    PermissionLevel getPermissionLevel();

    /**
     * 在指定工作目录上下文中执行工具。
     *
     * @param args       LLM 传入的 JSON 参数
     * @param workingDir Worker 工作目录（沙盒根）
     * @return 执行结果
     */
    ToolResult execute(JsonNode args, String workingDir);

    /** 构造 LLM 可识别的 ToolDefinition */
    default ToolDefinition toDefinition() {
        return new ToolDefinition(getName(), getDescription(), getParameterSchema());
    }

    enum PermissionLevel {
        SAFE,       // 只读，无副作用
        MODERATE,   // 有副作用，沙盒内
        DANGEROUS   // 可能影响系统
    }
}
