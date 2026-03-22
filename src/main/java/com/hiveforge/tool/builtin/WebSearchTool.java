package com.hiveforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveforge.tool.Tool;
import com.hiveforge.tool.ToolResult;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * WebSearchTool — 通过 HTTP 调用搜索 API 获取互联网信息。
 *
 * 支持的搜索后端（按优先级）：
 * 1. Tavily API（推荐，专为 AI Agent 设计）
 * 2. SerpAPI（Google 搜索代理）
 * 3. 兜底：使用 DuckDuckGo Instant Answer API（免费，无需 API Key）
 *
 * 通过环境变量配置：
 * - TAVILY_API_KEY: Tavily API Key
 * - SERP_API_KEY: SerpAPI Key
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** 搜索结果最大返回长度 */
    private static final int MAX_RESULT_CHARS = 6000;

    private final String tavilyApiKey;
    private final String serpApiKey;

    public WebSearchTool(OkHttpClient httpClient, ObjectMapper objectMapper, String tavilyApiKey, String serpApiKey) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.tavilyApiKey = tavilyApiKey;
        this.serpApiKey = serpApiKey;

        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            log.info("[WebSearch] Using Tavily API backend");
        } else if (serpApiKey != null && !serpApiKey.isBlank()) {
            log.info("[WebSearch] Using SerpAPI backend");
        } else {
            log.info("[WebSearch] Using DuckDuckGo fallback (no API key configured)");
        }
    }

    @Override
    public String getName() { return "web_search"; }

    @Override
    public String getDescription() {
        return "搜索互联网获取信息。返回搜索结果摘要，包含标题、摘要和来源 URL。"
                + "适用于查找最新数据、新闻、文档、公开信息等。";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "搜索关键词，如 'Apple 2024 Q4 revenue'"
                },
                "max_results": {
                  "type": "integer",
                  "description": "最大返回结果数，默认 5",
                  "default": 5
                }
              },
              "required": ["query"]
            }
            """;
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.MODERATE; }

    @Override
    public ToolResult execute(JsonNode args, String workingDir) {
        String query = args.path("query").asText("").trim();
        int maxResults = args.path("max_results").asInt(5);

        if (query.isEmpty()) {
            return new ToolResult(false, "Missing required parameter: query");
        }

        log.info("[WebSearch] Searching: '{}'", query);

        // 按优先级选择搜索后端
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            return searchWithTavily(query, maxResults);
        } else if (serpApiKey != null && !serpApiKey.isBlank()) {
            return searchWithSerp(query, maxResults);
        } else {
            return searchWithDuckDuckGo(query);
        }
    }

    // ===== Tavily API =====
    private ToolResult searchWithTavily(String query, int maxResults) {
        try {
            String requestBody = objectMapper.writeValueAsString(java.util.Map.of(
                    "api_key", tavilyApiKey,
                    "query", query,
                    "max_results", maxResults,
                    "include_answer", true,
                    "search_depth", "basic"
            ));

            Request request = new Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(RequestBody.create(requestBody,
                            MediaType.get("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new ToolResult(false, "Tavily API error: " + response.code());
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                StringBuilder sb = new StringBuilder();

                // 直接回答
                String answer = root.path("answer").asText("");
                if (!answer.isEmpty()) {
                    sb.append("**AI Answer**: ").append(answer).append("\n\n");
                }

                // 搜索结果
                JsonNode results = root.path("results");
                if (results.isArray()) {
                    sb.append("**Search Results**:\n\n");
                    int i = 0;
                    for (JsonNode r : results) {
                        if (++i > maxResults) break;
                        sb.append(i).append(". **").append(r.path("title").asText("")).append("**\n");
                        sb.append("   ").append(r.path("content").asText("")).append("\n");
                        sb.append("   Source: ").append(r.path("url").asText("")).append("\n\n");
                    }
                }

                return new ToolResult(true, truncate(sb.toString()));
            }

        } catch (Exception e) {
            log.error("[WebSearch] Tavily search failed", e);
            return new ToolResult(false, "Search failed: " + e.getMessage());
        }
    }

    // ===== SerpAPI =====
    private ToolResult searchWithSerp(String query, int maxResults) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://serpapi.com/search.json?q=" + encoded
                    + "&api_key=" + serpApiKey
                    + "&num=" + maxResults;

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new ToolResult(false, "SerpAPI error: " + response.code());
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                StringBuilder sb = new StringBuilder();
                sb.append("**Search Results for**: ").append(query).append("\n\n");

                // Answer box
                JsonNode answerBox = root.path("answer_box");
                if (!answerBox.isMissingNode()) {
                    sb.append("**Answer**: ").append(answerBox.path("answer").asText(
                            answerBox.path("snippet").asText(""))).append("\n\n");
                }

                // Organic results
                JsonNode organic = root.path("organic_results");
                if (organic.isArray()) {
                    int i = 0;
                    for (JsonNode r : organic) {
                        if (++i > maxResults) break;
                        sb.append(i).append(". **").append(r.path("title").asText("")).append("**\n");
                        sb.append("   ").append(r.path("snippet").asText("")).append("\n");
                        sb.append("   Source: ").append(r.path("link").asText("")).append("\n\n");
                    }
                }

                return new ToolResult(true, truncate(sb.toString()));
            }

        } catch (Exception e) {
            log.error("[WebSearch] SerpAPI search failed", e);
            return new ToolResult(false, "Search failed: " + e.getMessage());
        }
    }

    // ===== DuckDuckGo Instant Answer (兜底，免费) =====
    private ToolResult searchWithDuckDuckGo(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1";

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new ToolResult(false, "DuckDuckGo API error: " + response.code());
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                StringBuilder sb = new StringBuilder();
                sb.append("**Search Results for**: ").append(query).append("\n\n");

                // Abstract
                String abstractText = root.path("AbstractText").asText("");
                if (!abstractText.isEmpty()) {
                    sb.append("**Summary**: ").append(abstractText).append("\n");
                    sb.append("Source: ").append(root.path("AbstractURL").asText("")).append("\n\n");
                }

                // Related topics
                JsonNode related = root.path("RelatedTopics");
                if (related.isArray() && !related.isEmpty()) {
                    sb.append("**Related**:\n\n");
                    int i = 0;
                    for (JsonNode topic : related) {
                        if (++i > 5) break;
                        String text = topic.path("Text").asText("");
                        String firstUrl = topic.path("FirstURL").asText("");
                        if (!text.isEmpty()) {
                            sb.append(i).append(". ").append(text).append("\n");
                            if (!firstUrl.isEmpty()) {
                                sb.append("   ").append(firstUrl).append("\n");
                            }
                            sb.append("\n");
                        }
                    }
                }

                if (sb.toString().trim().equals("**Search Results for**: " + query)) {
                    sb.append("No direct results found. Try refining your search query.\n");
                    sb.append("Note: Using DuckDuckGo Instant Answer API (limited). ");
                    sb.append("Configure TAVILY_API_KEY for better results.\n");
                }

                return new ToolResult(true, truncate(sb.toString()));
            }

        } catch (Exception e) {
            log.error("[WebSearch] DuckDuckGo search failed", e);
            return new ToolResult(false, "Search failed: " + e.getMessage());
        }
    }

    private String truncate(String text) {
        if (text.length() <= MAX_RESULT_CHARS) return text;
        return text.substring(0, MAX_RESULT_CHARS)
                + "\n... (truncated, total " + text.length() + " chars)";
    }
}
