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
 * FileWriteTool — 将内容写入 Worker 的 output/ 目录。
 *
 * 安全约束：
 * - 只允许写入 output/ 子目录（比 FileRead 更严格）
 * - 自动创建不存在的父目录
 * - 文件大小限制，防止磁盘填满
 * - 路径不能逃逸出 output/ 目录
 */
public class FileWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    /** 单次写入最大内容长度 */
    private static final int MAX_CONTENT_LENGTH = 1024 * 1024; // 1MB

    @Override
    public String getName() { return "file_write"; }

    @Override
    public String getDescription() {
        return "将内容写入 Worker 的 output/ 目录下的文件。路径相对于 output/ 目录。"
                + "自动创建不存在的父目录。用于保存分析报告、数据文件等交付物。";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "文件路径（相对于 output/ 目录），如 'report.md' 或 'data/results.csv'"
                },
                "content": {
                  "type": "string",
                  "description": "要写入的文件内容"
                }
              },
              "required": ["path", "content"]
            }
            """;
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.MODERATE; }

    @Override
    public ToolResult execute(JsonNode args, String workingDir) {
        String filePath = args.path("path").asText("").trim();
        String content = args.path("content").asText("");

        if (filePath.isEmpty()) {
            return new ToolResult(false, "Missing required parameter: path");
        }

        if (content.length() > MAX_CONTENT_LENGTH) {
            return new ToolResult(false, String.format(
                    "Content too large: %d chars (max %d chars)",
                    content.length(), MAX_CONTENT_LENGTH));
        }

        try {
            // 限定写入 output/ 子目录
            Path resolved = SandboxGuard.resolveInSubDir(workingDir, "output", filePath);

            // 自动创建父目录
            Files.createDirectories(resolved.getParent());

            // 写入文件
            Files.writeString(resolved, content);

            String relativePath = "output/" + filePath;
            log.info("[FileWrite] Written {} chars to {}", content.length(), relativePath);

            return new ToolResult(true, String.format(
                    "Successfully written %d chars to %s", content.length(), relativePath));

        } catch (SandboxGuard.SandboxViolationException e) {
            return new ToolResult(false, e.getMessage());
        } catch (Exception e) {
            log.error("[FileWrite] Failed to write: {}", filePath, e);
            return new ToolResult(false, "Write error: " + e.getMessage());
        }
    }
}
