package com.hiveforge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${hiveforge.llm.api-key:}")
    private String apiKey;

    @Value("${hiveforge.llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${hiveforge.llm.default-model:gpt-4o}")
    private String defaultModel;

    @Value("${hiveforge.llm.worker-model:gpt-4o-mini}")
    private String workerModel;

    public LlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 简单对话 — 仅 user message，使用默认模型。
     */
    public String chat(String userMessage) {
        return chatWithSystem(null, userMessage);
    }

    /**
     * 带 system prompt 的对话 — 使用默认模型。
     */
    public String chatWithSystem(String systemPrompt, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(userMessage));

        ChatResponse resp = chatWithTools(defaultModel, messages, null);
        return resp.getContent() != null ? resp.getContent() : "";
    }

    /**
     * 多轮对话 + Function Calling — ReAct 循环的核心。
     *
     * 对应 OpenAI Chat Completions API：
     * - messages: 完整的对话历史（system / user / assistant / tool）
     * - tools: 可用工具列表（转为 OpenAI tools[] 格式）
     * - 返回 ChatResponse，可能包含 content 和/或 tool_calls
     *
     * @param model    模型名称
     * @param messages 对话历史
     * @param tools    可用工具定义列表（null 或空则不启用 function calling）
     * @return LLM 响应
     */
    public ChatResponse chatWithTools(String model, List<ChatMessage> messages,
                                       List<ToolDefinition> tools) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model != null ? model : defaultModel);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 4096);

            // 序列化 messages
            ArrayNode messagesArray = requestBody.putArray("messages");
            for (ChatMessage msg : messages) {
                messagesArray.add(msg.toJson(objectMapper));
            }

            // 序列化 tools（如果有）
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsArray = requestBody.putArray("tools");
                for (ToolDefinition tool : tools) {
                    toolsArray.add(tool.toJson(objectMapper));
                }
                // 让 LLM 自己决定是否调用工具
                requestBody.put("tool_choice", "auto");
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("[LLM] Request to {}, model={}, messages={}, tools={}",
                    baseUrl, model, messages.size(),
                    tools != null ? tools.size() : 0);

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "no body";
                    log.error("[LLM] API error: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("LLM API error: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                return parseResponse(responseBody);
            }

        } catch (IOException e) {
            log.error("[LLM] call failed", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 Worker 模型发起对话（通常是更轻量/便宜的模型）
     */
    public ChatResponse chatWithWorkerModel(List<ChatMessage> messages,
                                             List<ToolDefinition> tools) {
        return chatWithTools(workerModel, messages, tools);
    }

    /**
     * 解析 OpenAI API 响应，提取 content 和 tool_calls
     */
    private ChatResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("");

            // 提取 content（可能为 null）
            String content = null;
            if (message.has("content") && !message.get("content").isNull()) {
                content = message.get("content").asText();
            }

            // 提取 tool_calls
            List<ToolCall> toolCalls = new ArrayList<>();
            if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
                for (JsonNode tcNode : message.get("tool_calls")) {
                    String id = tcNode.path("id").asText();
                    String name = tcNode.path("function").path("name").asText();
                    String arguments = tcNode.path("function").path("arguments").asText();
                    toolCalls.add(new ToolCall(id, name, arguments));
                }
            }

            return new ChatResponse(content, toolCalls.isEmpty() ? null : toolCalls, finishReason);

        } catch (Exception e) {
            log.error("[LLM] Failed to parse response: {}", responseBody, e);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
    }
}
