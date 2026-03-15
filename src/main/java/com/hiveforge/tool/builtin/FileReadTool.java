package com.hiveforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiveforge.tool.SandboxGuard;
import com.hiveforge.tool.Tool;
import com.hiveforge.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FileReadTool — 读取 Worker 工作目录内的文件。
 *
 * 安全约束：
 * - 路径必须在工作目录内（SandboxGuard 验证）
 * - 不能读取二进制文件（按大小限制）
 * - 输出自动截断，防止撑爆 LLM 上下文
 */
public class FileReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    /** 最大可读取的文件大小 (bytes) */
    private static final long MAX_FILE_SIZE = 512 * 1024; // 512KB

    /** 输出截断长度 */
    private static final int MAX_OUTPUT_CHARS = 8000;

    @Override
    public String getName() { return "file_read"; }

    @Override
    public String getDescription() {
        return "读取工作目录内的文件内容。路径相对于 Worker 工作目录。"
                + "可读取代码、文本、Markdown、CSV、JSON 等文本文件。";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "文件路径（相对于工作目录），如 'SOUL.md' 或 'output/report.md'"
                }
              },
              "required": ["path"]
            }
            """;
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.SAFE; }

    @Override
    public ToolResult execute(JsonNode args, String workingDir) {
        String filePath = args.path("path").asText("").trim();
        if (filePath.isEmpty()) {
            return new ToolResult(false, "Missing required parameter: path");
        }

        try {
            Path resolved = SandboxGuard.resolve(workingDir, filePath);

            if (!Files.exists(resolved)) {
                return new ToolResult(false, "File not found: " + filePath);
            }

            if (Files.isDirectory(resolved)) {
                return new ToolResult(false, "Path is a directory, not a file: " + filePath
                        + ". Use file_list tool to list directory contents.");
            }

            long size = Files.size(resolved);
            if (size > MAX_FILE_SIZE) {
                return new ToolResult(false, String.format(
                        "File too large: %s (%d bytes, max %d bytes)",
                        filePath, size, MAX_FILE_SIZE));
            }

            if (size == 0) {
                return new ToolResult(true, "(empty file)");
            }

            String content = Files.readString(resolved);
            return new ToolResult(true, truncate(content));

        } catch (SandboxGuard.SandboxViolationException e) {
            return new ToolResult(false, e.getMessage());
        } catch (Exception e) {
            log.error("[FileRead] Failed to read: {}", filePath, e);
            return new ToolResult(false, "Read error: " + e.getMessage());
        }
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS)
                + "\n\n... (truncated, total " + text.length() + " chars)";
    }
}
