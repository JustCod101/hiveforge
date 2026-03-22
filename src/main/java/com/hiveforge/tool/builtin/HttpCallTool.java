package com.hiveforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiveforge.tool.Tool;
import com.hiveforge.tool.ToolResult;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * HttpCallTool — 发起 HTTP 请求获取 URL 内容。
 *
 * 支持 GET / POST 方法，可用于：
 * - 获取网页内容
 * - 调用公开 REST API
 * - 下载 JSON/CSV 数据
 *
 * 安全约束：
 * - 禁止访问内网地址（127.0.0.1、10.x、192.168.x 等）
 * - 超时控制（连接 10s，读取 30s）
 * - 响应体大小限制（1MB）
 * - 只支持 HTTP/HTTPS 协议
 */
public class HttpCallTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(HttpCallTool.class);

    private final OkHttpClient httpClient;

    /** 响应体最大长度 */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024; // 1MB

    /** 输出截断长度 */
    private static final int MAX_OUTPUT_CHARS = 8000;

    /** 禁止访问的内网地址前缀 */
    private static final Set<String> BLOCKED_HOST_PREFIXES = Set.of(
            "127.", "10.", "192.168.", "172.16.", "172.17.", "172.18.",
            "172.19.", "172.20.", "172.21.", "172.22.", "172.23.",
            "172.24.", "172.25.", "172.26.", "172.27.", "172.28.",
            "172.29.", "172.30.", "172.31.", "0.", "169.254."
    );

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "metadata.google.internal",
            "instance-data", "metadata"
    );

    public HttpCallTool(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getName() { return "http_call"; }

    @Override
    public String getDescription() {
        return "发起 HTTP 请求获取 URL 内容。支持 GET 和 POST 方法。"
                + "可用于获取网页、调用 API、下载数据。禁止访问内网地址。";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "请求的完整 URL，如 'https://api.example.com/data'"
                },
                "method": {
                  "type": "string",
                  "description": "HTTP 方法，GET 或 POST，默认 GET",
                  "enum": ["GET", "POST"],
                  "default": "GET"
                },
                "headers": {
                  "type": "object",
                  "description": "请求头（可选），如 {\"Accept\": \"application/json\"}",
                  "additionalProperties": { "type": "string" }
                },
                "body": {
                  "type": "string",
                  "description": "POST 请求体（仅 POST 方法时使用）"
                }
              },
              "required": ["url"]
            }
            """;
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.MODERATE; }

    @Override
    public ToolResult execute(JsonNode args, String workingDir) {
        String url = args.path("url").asText("").trim();
        String method = args.path("method").asText("GET").toUpperCase();

        if (url.isEmpty()) {
            return new ToolResult(false, "Missing required parameter: url");
        }

        // ===== URL 安全检查 =====
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new ToolResult(false, "Only HTTP/HTTPS URLs are allowed");
        }

        String host = extractHost(url);
        if (isBlockedHost(host)) {
            log.warn("[HttpCall] Blocked access to internal host: {}", host);
            return new ToolResult(false, "Access to internal/private addresses is blocked: " + host);
        }

        try {
            // ===== 构建请求 =====
            Request.Builder reqBuilder = new Request.Builder().url(url);

            // 添加自定义 headers
            JsonNode headers = args.path("headers");
            if (headers.isObject()) {
                headers.fields().forEachRemaining(entry ->
                        reqBuilder.addHeader(entry.getKey(), entry.getValue().asText("")));
            }

            // User-Agent
            reqBuilder.addHeader("User-Agent", "HiveForge-Agent/1.0");

            // 设置方法
            if ("POST".equals(method)) {
                String body = args.path("body").asText("");
                MediaType mediaType = MediaType.get("application/json; charset=utf-8");
                reqBuilder.post(RequestBody.create(body, mediaType));
            } else {
                reqBuilder.get();
            }

            log.info("[HttpCall] {} {}", method, url);

            // ===== 执行请求 =====
            try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                int statusCode = response.code();
                String contentType = response.header("Content-Type", "unknown");

                StringBuilder sb = new StringBuilder();
                sb.append("HTTP ").append(statusCode).append(" ").append(response.message()).append("\n");
                sb.append("Content-Type: ").append(contentType).append("\n\n");

                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    // 检查内容长度
                    long contentLength = responseBody.contentLength();
                    if (contentLength > MAX_RESPONSE_BYTES) {
                        sb.append("(Response too large: ").append(contentLength).append(" bytes, max ")
                                .append(MAX_RESPONSE_BYTES).append(" bytes)");
                    } else {
                        String bodyStr = responseBody.string();
                        sb.append(bodyStr);
                    }
                }

                boolean success = statusCode >= 200 && statusCode < 400;
                return new ToolResult(success, truncate(sb.toString()));
            }

        } catch (Exception e) {
            log.error("[HttpCall] Request failed: {} {}", method, url, e);
            return new ToolResult(false, "HTTP request failed: " + e.getMessage());
        }
    }

    private String extractHost(String url) {
        try {
            // 简单提取 host（不用 java.net.URI 避免格式校验过严）
            String afterProtocol = url.replaceFirst("https?://", "");
            int slashIdx = afterProtocol.indexOf('/');
            String hostPort = slashIdx > 0 ? afterProtocol.substring(0, slashIdx) : afterProtocol;
            int colonIdx = hostPort.indexOf(':');
            return colonIdx > 0 ? hostPort.substring(0, colonIdx) : hostPort;
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isBlockedHost(String host) {
        if (host == null) return true;
        String lower = host.toLowerCase();

        if (BLOCKED_HOSTS.contains(lower)) return true;

        for (String prefix : BLOCKED_HOST_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }

        return false;
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS)
                + "\n... (truncated, total " + text.length() + " chars)";
    }
}
