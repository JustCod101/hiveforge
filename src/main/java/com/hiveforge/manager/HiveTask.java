package com.hiveforge.manager;

public class HiveTask {

    private String id;
    private String userQuery;
    private String taskPlan;
    private String status;
    private int workerCount;
    private int totalTokens;
    private int totalCostCents;
    private String finalReport;
    private String createdAt;
    private String completedAt;

    public HiveTask() {}

    public HiveTask(String id, String userQuery) {
        this.id = id;
        this.userQuery = userQuery;
        this.status = "PLANNING";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserQuery() { return userQuery; }
    public void setUserQuery(String userQuery) { this.userQuery = userQuery; }

    public String getTaskPlan() { return taskPlan; }
    public void setTaskPlan(String taskPlan) { this.taskPlan = taskPlan; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = workerCount; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public int getTotalCostCents() { return totalCostCents; }
    public void setTotalCostCents(int totalCostCents) { this.totalCostCents = totalCostCents; }

    public String getFinalReport() { return finalReport; }
    public void setFinalReport(String finalReport) { this.finalReport = finalReport; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
}
