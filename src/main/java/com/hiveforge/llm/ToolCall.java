package com.hiveforge.llm;

/**
 * LLM 返回的 tool_call — 对应 OpenAI 的 tool_calls[].function
 */
public class ToolCall {

    private final String id;
    private final String name;
    private final String arguments;   // JSON string

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getArguments() { return arguments; }

    @Override
    public String toString() {
        return "ToolCall{name='" + name + "', args=" + arguments + "}";
    }
}
