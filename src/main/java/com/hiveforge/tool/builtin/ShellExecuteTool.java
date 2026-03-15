package com.hiveforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiveforge.tool.Tool;
import com.hiveforge.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ShellExecuteTool — 在 Worker 工作目录内执行 shell 命令。
 *
 * 沙盒安全措施：
 * 1. 工作目录限定 — ProcessBuilder.directory() 设为 Worker 目录
 * 2. 超时控制 — 默认 30 秒，超时后 destroyForcibly()
 * 3. 命令黑名单 — 禁止 rm -rf /、sudo、chmod 777 等危险操作
 * 4. 输出截断 — 防止 stdout 炸裂
 * 5. stderr 合并 — 统一收集
 */
public class ShellExecuteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ShellExecuteTool.class);

    /** 命令执行超时（秒） */
    private static final int TIMEOUT_SECONDS = 30;

    /** 输出最大长度 */
    private static final int MAX_OUTPUT_CHARS = 8000;

    /**
     * 命令黑名单 — 这些命令模式会被直接拒绝执行。
     * 匹配规则：正则 Pattern，任何一个匹配即拒绝。
     */
    private static final List<Pattern> COMMAND_BLACKLIST = List.of(
            // 危险删除操作
            Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?/(?!tmp/hive)"),  // rm -rf / (但允许 /tmp/hive 内)
            Pattern.compile("rm\\s+-[a-zA-Z]*r[a-zA-Z]*\\s+-[a-zA-Z]*f[a-zA-Z]*\\s+/"),
            // 提权
            Pattern.compile("\\bsudo\\b"),
            Pattern.compile("\\bsu\\s"),
            Pattern.compile("\\bchmod\\s+777\\b"),
            Pattern.compile("\\bchown\\b"),
            // 网络攻击
            Pattern.compile("\\bnc\\s+-[a-zA-Z]*l"),    // netcat listen
            Pattern.compile("\\bnmap\\b"),
            // 系统破坏
            Pattern.compile("\\bmkfs\\b"),
            Pattern.compile("\\bdd\\s+if="),
            Pattern.compile("::\\(\\)\\{"),              // fork bomb
            Pattern.compile("\\bshutdown\\b"),
            Pattern.compile("\\breboot\\b"),
            Pattern.compile("\\bkill\\s+-9\\s+1\\b"),   // kill init
            // 敏感文件访问
            Pattern.compile("\\bcat\\s+/etc/(passwd|shadow)"),
            // 环境篡改
            Pattern.compile("\\bexport\\s+PATH="),
            Pattern.compile("\\bunset\\s+PATH"),
            // curl/wget 下载并执行
            Pattern.compile("curl.*\\|\\s*(sh|bash)"),
            Pattern.compile("wget.*\\|\\s*(sh|bash)")
    );

    /** 允许的命令前缀白名单（用于快速放行常见安全命令） */
    private static final Set<String> SAFE_COMMAND_PREFIXES = Set.of(
            "ls", "cat", "head", "tail", "grep", "find", "wc",
            "echo", "printf", "date", "pwd", "env",
            "sort", "uniq", "cut", "tr", "sed", "awk",
            "python3", "python", "node", "java", "javac",
            "pip", "npm", "mvn", "gradle",
            "git", "diff", "patch",
            "curl", "wget", "jq"
    );

    @Override
    public String getName() { return "shell_exec"; }

    @Override
    public String getDescription() {
        return "在 Worker 工作目录内执行 shell 命令。"
                + "适用于运行脚本、编译代码、静态分析、文本处理等。"
                + "超时 30 秒。禁止执行系统危险命令（sudo/rm -rf / 等）。";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "要执行的 shell 命令，如 'ls -la' 或 'python3 script.py'"
                }
              },
              "required": ["command"]
            }
            """;
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.DANGEROUS; }

    @Override
    public ToolResult execute(JsonNode args, String workingDir) {
        String command = args.path("command").asText("").trim();

        if (command.isEmpty()) {
            return new ToolResult(false, "Missing required parameter: command");
        }

        // ===== 命令安全检查 =====
        String blacklistMatch = checkBlacklist(command);
        if (blacklistMatch != null) {
            log.warn("[Shell] Blocked dangerous command: '{}', matched rule: {}",
                    command, blacklistMatch);
            return new ToolResult(false,
                    "Command blocked by security policy: " + blacklistMatch
                    + ". Dangerous system commands are not allowed.");
        }

        try {
            // ===== 执行命令 =====
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(Path.of(workingDir).toFile());
            pb.redirectErrorStream(true);

            // 清理危险环境变量
            var env = pb.environment();
            env.remove("HISTFILE");  // 不记录命令历史

            log.info("[Shell] Executing in '{}': {}", workingDir, command);

            Process process = pb.start();

            // 读取输出
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            // 超时控制
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Shell] Command timed out after {}s: {}", TIMEOUT_SECONDS, command);
                return new ToolResult(false, String.format(
                        "Command timed out after %d seconds. Partial output:\n%s",
                        TIMEOUT_SECONDS, truncate(output)));
            }

            int exitCode = process.exitValue();
            String truncatedOutput = truncate(output);

            String result = "exit_code=" + exitCode + "\n" + truncatedOutput;

            if (exitCode != 0) {
                log.info("[Shell] Command exited with code {}: {}", exitCode, command);
            }

            return new ToolResult(exitCode == 0, result);

        } catch (Exception e) {
            log.error("[Shell] Execution failed: {}", command, e);
            return new ToolResult(false, "Shell execution error: " + e.getMessage());
        }
    }

    /**
     * 检查命令是否匹配黑名单。
     * @return 匹配的规则描述（如果匹配），或 null（安全）
     */
    private String checkBlacklist(String command) {
        for (Pattern pattern : COMMAND_BLACKLIST) {
            if (pattern.matcher(command).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS)
                + "\n... (truncated, total " + text.length() + " chars)";
    }
}
