package com.hiveforge.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry toolRegistry;

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Execute a tool within a given working directory context.
     * Tool implementations will be added as the system evolves.
     */
    public ToolResult executeInContext(String toolName, String input, String workingDir) {
        log.info("[ToolExecutor] Executing tool '{}' in context '{}'", toolName, workingDir);

        return switch (toolName) {
            case "file_read" -> executeFileRead(input, workingDir);
            case "file_write" -> executeFileWrite(input, workingDir);
            default -> new ToolResult(false, "Tool not implemented: " + toolName);
        };
    }

    private ToolResult executeFileRead(String input, String workingDir) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(workingDir, input);
            String content = java.nio.file.Files.readString(path);
            return new ToolResult(true, content);
        } catch (Exception e) {
            return new ToolResult(false, "Failed to read file: " + e.getMessage());
        }
    }

    private ToolResult executeFileWrite(String input, String workingDir) {
        try {
            // input format: "filename:::content"
            int sep = input.indexOf(":::");
            if (sep < 0) {
                return new ToolResult(false, "Invalid input format. Expected: filename:::content");
            }
            String filename = input.substring(0, sep);
            String content = input.substring(sep + 3);
            java.nio.file.Path path = java.nio.file.Path.of(workingDir, filename);
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, content);
            return new ToolResult(true, "File written: " + path);
        } catch (Exception e) {
            return new ToolResult(false, "Failed to write file: " + e.getMessage());
        }
    }
}
