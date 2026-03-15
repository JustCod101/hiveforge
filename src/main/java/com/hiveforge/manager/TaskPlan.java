package com.hiveforge.manager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskPlan {

    @JsonProperty("task_summary")
    private String taskSummary;

    private List<WorkerPlan> workers;

    @JsonProperty("execution_strategy")
    private String executionStrategy;

    @JsonProperty("aggregation_instruction")
    private String aggregationInstruction;

    public TaskPlan() {}

    public String getTaskSummary() { return taskSummary; }
    public void setTaskSummary(String taskSummary) { this.taskSummary = taskSummary; }

    public List<WorkerPlan> getWorkers() { return workers; }
    public void setWorkers(List<WorkerPlan> workers) { this.workers = workers; }

    public String getExecutionStrategy() { return executionStrategy; }
    public void setExecutionStrategy(String executionStrategy) { this.executionStrategy = executionStrategy; }

    public String getAggregationInstruction() { return aggregationInstruction; }
    public void setAggregationInstruction(String aggregationInstruction) { this.aggregationInstruction = aggregationInstruction; }
}
