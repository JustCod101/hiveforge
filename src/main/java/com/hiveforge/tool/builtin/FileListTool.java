package com.hiveforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiveforge.tool.SandboxGuard;
import com.hiveforge.tool.Tool;
import com.hiveforge.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * FileListTool — 列出工作目录内指定路径下的文件和子目录。
 */
public class FileListTool implements Tool {

    @Override
    public String getName() { return "file_list"; }

    @Override
    public String getDescription() {
        return "列出工作目录内指定路径下的文件和子目录。默认列出根目录。";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "目录路径（相对于工作目录），默认 '.'",
                  "default": "."
                }
              },
              "required": []
            }
            """;
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.SAFE; }

    @Override
    public ToolResult execute(JsonNode args, String workingDir) {
        String dirPath = args.path("path").asText(".");
        if (dirPath.isBlank()) dirPath = ".";

        try {
            Path resolved = SandboxGuard.resolve(workingDir, dirPath);

            if (!Files.isDirectory(resolved)) {
                return new ToolResult(false, "Not a directory: " + dirPath);
            }

            try (var entries = Files.list(resolved)) {
                String listing = entries
                        .map(p -> {
                            String name = p.getFileName().toString();
                            if (Files.isDirectory(p)) {
                                return name + "/";
                            }
                            try {
                                long size = Files.size(p);
                                return name + "  (" + humanReadableSize(size) + ")";
                            } catch (Exception e) {
                                return name;
                            }
                        })
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return new ToolResult(true, listing.isEmpty() ? "(empty directory)" : listing);
            }

        } catch (SandboxGuard.SandboxViolationException e) {
            return new ToolResult(false, e.getMessage());
        } catch (Exception e) {
            return new ToolResult(false, "List error: " + e.getMessage());
        }
    }

    private String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
