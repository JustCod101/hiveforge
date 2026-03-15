package com.hiveforge.manager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerPlan {

    private String name;
    private String role;

    @JsonProperty("tools_needed")
    private List<String> toolsNeeded;

    @JsonProperty("task_description")
    private String taskDescription;

    @JsonProperty("depends_on")
    private List<String> dependsOn;

    private int priority;
    private String template;

    public WorkerPlan() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public List<String> getToolsNeeded() { return toolsNeeded; }
    public void setToolsNeeded(List<String> toolsNeeded) { this.toolsNeeded = toolsNeeded; }

    public String getTaskDescription() { return taskDescription; }
    public void setTaskDescription(String taskDescription) { this.taskDescription = taskDescription; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
}
