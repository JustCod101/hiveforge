package com.hiveforge.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveforge.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 工具执行器 — Worker 的 "手和脚"。
 *
 * 所有工具执行都限定在 Worker 工作目录内（沙盒隔离）。
 * 支持 JSON 参数解析（对接 OpenAI function calling 的 arguments 格式）。
 */
@Service
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private static final int SHELL_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH = 8000;

    private final ObjectMapper objectMapper;

    /**
     * 内置工具定义表 — name → ToolDefinition。
     * WorkerEngine 根据 AGENTS.md 声明的工具名，从这里过滤出该 Worker 可用的子集。
     */
    private static final Map<String, ToolDefinition> BUILTIN_TOOLS = new LinkedHashMap<>();

    static {
        BUILTIN_TOOLS.put("file_read", new ToolDefinition(
                "file_read",
                "读取工作目录内的文件内容。路径相对于 Worker 工作目录。",
                """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "文件路径（相对于工作目录）" }
                  },
                  "required": ["path"]
                }
                """));

        BUILTIN_TOOLS.put("file_write", new ToolDefinition(
                "file_write",
                "将内容写入工作目录内的文件。自动创建不存在的父目录。路径相对于 Worker 工作目录。",
                """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "文件路径（相对于工作目录）" },
                    "content": { "type": "string", "description": "要写入的文件内容" }
                  },
                  "required": ["path", "content"]
                }
                """));

        BUILTIN_TOOLS.put("file_list", new ToolDefinition(
                "file_list",
                "列出工作目录内指定路径下的文件和子目录。",
                """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "目录路径（相对于工作目录），默认为 '.'", "default": "." }
                  },
                  "required": []
                }
                """));

        BUILTIN_TOOLS.put("web_search", new ToolDefinition(
                "web_search",
                "搜索互联网获取信息。返回搜索结果摘要。",
                """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string", "description": "搜索关键词" }
                  },
                  "required": ["query"]
                }
                """));

        BUILTIN_TOOLS.put("http_call", new ToolDefinition(
                "http_call",
                "发起 HTTP 请求获取 URL 内容。支持 GET 请求。",
                """
                {
                  "type": "object",
                  "properties": {
                    "url": { "type": "string", "description": "请求的 URL" },
                    "method": { "type": "string", "description": "HTTP 方法，默认 GET", "default": "GET" }
                  },
                  "required": ["url"]
                }
                """));

        BUILTIN_TOOLS.put("shell_exec", new ToolDefinition(
                "shell_exec",
                "在 Worker 工作目录内执行 shell 命令。用于运行脚本、编译、静态分析等。超时 30 秒。",
                """
                {
                  "type": "object",
                  "properties": {
                    "command": { "type": "string", "description": "要执行的 shell 命令" }
                  },
                  "required": ["command"]
                }
                """));

        BUILTIN_TOOLS.put("calculate", new ToolDefinition(
                "calculate",
                "执行数学表达式计算。支持基本运算和常用函数。",
                """
                {
                  "type": "object",
                  "properties": {
                    "expression": { "type": "string", "description": "数学表达式，如 '(100 - 80) / 80 * 100'" }
                  },
                  "required": ["expression"]
                }
                """));
    }

    public ToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 根据工具名获取 ToolDefinition。
     * WorkerEngine 用此方法过滤出 Worker 可用的工具子集。
     *
     * @return ToolDefinition 或 null（未知工具）
     */
    public ToolDefinition getDefinition(String toolName) {
        return BUILTIN_TOOLS.get(toolName);
    }

    /**
     * 在指定工作目录上下文中执行工具。
     * 所有文件路径操作都限定在 workingDir 内（沙盒隔离）。
     *
     * @param toolName   工具名称
     * @param argsJson   JSON 格式的参数（来自 LLM 的 function calling arguments）
     * @param workingDir Worker 工作目录（沙盒根）
     * @return 工具执行结果
     */
    public ToolResult executeInContext(String toolName, String argsJson, String workingDir) {
        log.info("[ToolExecutor] tool='{}', workingDir='{}'", toolName, workingDir);
        long start = System.currentTimeMillis();

        try {
            JsonNode args = objectMapper.readTree(argsJson);

            ToolResult result = switch (toolName) {
                case "file_read"   -> executeFileRead(args, workingDir);
                case "file_write"  -> executeFileWrite(args, workingDir);
                case "file_list"   -> executeFileList(args, workingDir);
                case "shell_exec"  -> executeShellExec(args, workingDir);
                case "calculate"   -> executeCalculate(args);
                case "web_search"  -> executeWebSearch(args);
                case "http_call"   -> executeHttpCall(args);
                default            -> new ToolResult(false, "Unknown tool: " + toolName);
            };

            long elapsed = System.currentTimeMillis() - start;
            log.info("[ToolExecutor] tool='{}' completed in {}ms, success={}",
                    toolName, elapsed, result.isSuccess());
            return result;

        } catch (Exception e) {
            log.error("[ToolExecutor] tool='{}' failed", toolName, e);
            return new ToolResult(false, "Tool execution error: " + e.getMessage());
        }
    }

    // ===== 文件读取 =====
    private ToolResult executeFileRead(JsonNode args, String workingDir) {
        String filePath = args.path("path").asText("");
        Path resolved = resolveSandboxPath(workingDir, filePath);
        if (resolved == null) {
            return new ToolResult(false, "Path escape denied: " + filePath);
        }

        try {
            if (!Files.exists(resolved)) {
                return new ToolResult(false, "File not found: " + filePath);
            }
            String content = Files.readString(resolved);
            return new ToolResult(true, truncate(content));
        } catch (Exception e) {
            return new ToolResult(false, "Failed to read: " + e.getMessage());
        }
    }

    // ===== 文件写入 =====
    private ToolResult executeFileWrite(JsonNode args, String workingDir) {
        String filePath = args.path("path").asText("");
        String content = args.path("content").asText("");
        Path resolved = resolveSandboxPath(workingDir, filePath);
        if (resolved == null) {
            return new ToolResult(false, "Path escape denied: " + filePath);
        }

        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
            return new ToolResult(true, "Written " + content.length() + " chars to " + filePath);
        } catch (Exception e) {
            return new ToolResult(false, "Failed to write: " + e.getMessage());
        }
    }

    // ===== 文件列表 =====
    private ToolResult executeFileList(JsonNode args, String workingDir) {
        String dirPath = args.path("path").asText(".");
        Path resolved = resolveSandboxPath(workingDir, dirPath);
        if (resolved == null) {
            return new ToolResult(false, "Path escape denied: " + dirPath);
        }

        try {
            if (!Files.isDirectory(resolved)) {
                return new ToolResult(false, "Not a directory: " + dirPath);
            }
            try (var entries = Files.list(resolved)) {
                String listing = entries
                        .map(p -> {
                            String name = p.getFileName().toString();
                            return Files.isDirectory(p) ? name + "/" : name;
                        })
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return new ToolResult(true, listing.isEmpty() ? "(empty directory)" : listing);
            }
        } catch (Exception e) {
            return new ToolResult(false, "Failed to list: " + e.getMessage());
        }
    }

    // ===== Shell 执行 =====
    private ToolResult executeShellExec(JsonNode args, String workingDir) {
        String command = args.path("command").asText("");
        if (command.isBlank()) {
            return new ToolResult(false, "Empty command");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(Path.of(workingDir).toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolResult(false, "Command timed out after " + SHELL_TIMEOUT_SECONDS + "s");
            }

            int exitCode = process.exitValue();
            String result = "exit_code=" + exitCode + "\n" + truncate(output);
            return new ToolResult(exitCode == 0, result);

        } catch (Exception e) {
            return new ToolResult(false, "Shell exec failed: " + e.getMessage());
        }
    }

    // ===== 数学计算 =====
    private ToolResult executeCalculate(JsonNode args) {
        String expression = args.path("expression").asText("");
        if (expression.isBlank()) {
            return new ToolResult(false, "Empty expression");
        }

        try {
            // 使用 JavaScript 引擎计算（JDK 17 可用 ProcessBuilder 调用 bc 或 python）
            ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                    "print(eval('" + expression.replace("'", "\\'") + "'))");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolResult(false, "Calculation timed out");
            }

            if (process.exitValue() != 0) {
                return new ToolResult(false, "Calculation error: " + output);
            }

            return new ToolResult(true, expression + " = " + output.trim());
        } catch (Exception e) {
            return new ToolResult(false, "Calculate failed: " + e.getMessage());
        }
    }

    // ===== Web 搜索（占位实现） =====
    private ToolResult executeWebSearch(JsonNode args) {
        String query = args.path("query").asText("");
        // TODO: 集成真实搜索 API（SerpAPI / Tavily / Bing）
        return new ToolResult(true,
                "[web_search placeholder] Query: '" + query + "'\n"
                + "To enable real search, configure a search API provider.\n"
                + "Returning empty results for now.");
    }

    // ===== HTTP 调用（占位实现） =====
    private ToolResult executeHttpCall(JsonNode args) {
        String url = args.path("url").asText("");
        String method = args.path("method").asText("GET");
        // TODO: 使用 OkHttpClient 真实请求
        return new ToolResult(true,
                "[http_call placeholder] " + method + " " + url + "\n"
                + "To enable real HTTP calls, configure the http_call tool.\n"
                + "Returning empty results for now.");
    }

    // ===== 沙盒路径验证 =====

    /**
     * 将相对路径解析为绝对路径，并验证它没有逃逸出工作目录。
     * 防止 Worker 通过 "../../../etc/passwd" 之类的路径访问系统文件。
     *
     * @return 安全的绝对路径，或 null 如果路径越界
     */
    private Path resolveSandboxPath(String workingDir, String relativePath) {
        try {
            Path base = Path.of(workingDir).toAbsolutePath().normalize();
            Path resolved = base.resolve(relativePath).toAbsolutePath().normalize();

            if (!resolved.startsWith(base)) {
                log.warn("[Sandbox] Path escape attempt: base={}, resolved={}", base, resolved);
                return null;
            }
            return resolved;
        } catch (Exception e) {
            log.warn("[Sandbox] Invalid path: {}", relativePath, e);
            return null;
        }
    }

    /**
     * 截断过长的输出，避免撑爆 LLM 上下文
     */
    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_LENGTH) return text;
        return text.substring(0, MAX_OUTPUT_LENGTH) + "\n... (truncated, total " + text.length() + " chars)";
    }
}
