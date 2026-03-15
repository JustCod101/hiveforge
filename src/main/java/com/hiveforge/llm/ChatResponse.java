package com.hiveforge.llm;

import java.util.List;

/**
 * LLM 响应 — 包含文本内容和/或 tool_calls。
 * 当 finish_reason=tool_calls 时，content 可能为 null 而 toolCalls 非空。
 * 当 finish_reason=stop 时，content 非空而 toolCalls 为空。
 */
public class ChatResponse {

    private final String content;
    private final List<ToolCall> toolCalls;
    private final String finishReason;

    public ChatResponse(String content, List<ToolCall> toolCalls, String finishReason) {
        this.content = content;
        this.toolCalls = toolCalls;
        this.finishReason = finishReason;
    }

    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public String getFinishReason() { return finishReason; }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
