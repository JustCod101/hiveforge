package com.hiveforge.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具定义 — 对应 OpenAI tools[] 中的 function 描述。
 * 在发送给 LLM 时转为 JSON Schema 格式，让 LLM 知道可以调用哪些工具。
 */
public class ToolDefinition {

    private final String name;
    private final String description;
    private final String parametersJson;  // JSON Schema string

    public ToolDefinition(String name, String description, String parametersJson) {
        this.name = name;
        this.description = description;
        this.parametersJson = parametersJson;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getParametersJson() { return parametersJson; }

    /**
     * 序列化为 OpenAI API 的 tools[] 元素格式
     */
    public ObjectNode toJson(ObjectMapper mapper) {
        try {
            ObjectNode toolNode = mapper.createObjectNode();
            toolNode.put("type", "function");

            ObjectNode funcNode = toolNode.putObject("function");
            funcNode.put("name", name);
            funcNode.put("description", description);
            funcNode.set("parameters", mapper.readTree(parametersJson));

            return toolNode;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tool definition: " + name, e);
        }
    }
}
