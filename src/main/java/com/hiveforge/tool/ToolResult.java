package com.hiveforge.tool;

public class ToolResult {

    private final boolean success;
    private final String output;

    public ToolResult(boolean success, String output) {
        this.success = success;
        this.output = output;
    }

    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
}
