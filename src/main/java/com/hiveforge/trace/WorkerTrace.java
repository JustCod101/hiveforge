package com.hiveforge.trace;

public class WorkerTrace {

    private int id;
    private String workerId;
    private int stepIndex;
    private String stepType;
    private String content;
    private String toolName;
    private String toolInput;
    private String toolOutput;
    private Integer latencyMs;
    private String createdAt;

    public WorkerTrace() {}

    public WorkerTrace(String workerId, int stepIndex, String stepType, String content,
                       String toolName, String toolInput, String toolOutput) {
        this.workerId = workerId;
        this.stepIndex = stepIndex;
        this.stepType = stepType;
        this.content = content;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.toolOutput = toolOutput;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }

    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getToolInput() { return toolInput; }
    public void setToolInput(String toolInput) { this.toolInput = toolInput; }

    public String getToolOutput() { return toolOutput; }
    public void setToolOutput(String toolOutput) { this.toolOutput = toolOutput; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
