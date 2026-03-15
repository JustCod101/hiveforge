package com.hiveforge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * LLM 对话消息 — 对应 OpenAI Chat Completions API 的 message 对象。
 * 支持 system / user / assistant / tool 四种角色。
 */
public class ChatMessage {

    private final String role;
    private final String content;
    private final List<ToolCall> toolCalls;     // assistant 消息可能携带的 tool_calls
    private final String toolCallId;            // tool 消息需要关联的 tool_call_id

    private ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null);
    }

    public static ChatMessage assistantText(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }

    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }

    /**
     * 序列化为 OpenAI API 的 JSON message 格式
     */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);

        if (content != null) {
            node.put("content", content);
        } else if (!"assistant".equals(role)) {
            node.put("content", "");
        }

        // assistant 消息带 tool_calls
        if (toolCalls != null && !toolCalls.isEmpty()) {
            ArrayNode callsArray = node.putArray("tool_calls");
            for (ToolCall tc : toolCalls) {
                ObjectNode callNode = callsArray.addObject();
                callNode.put("id", tc.getId());
                callNode.put("type", "function");
                ObjectNode funcNode = callNode.putObject("function");
                funcNode.put("name", tc.getName());
                funcNode.put("arguments", tc.getArguments());
            }
            // OpenAI 要求 content 可为 null
            if (content == null) {
                node.putNull("content");
            }
        }

        // tool 消息需要 tool_call_id
        if ("tool".equals(role) && toolCallId != null) {
            node.put("tool_call_id", toolCallId);
        }

        return node;
    }
}
