package com.hiveforge.manager;

import com.hiveforge.worker.WorkerResult;

import java.util.List;

public class HiveResult {

    private final String taskId;
    private final String report;
    private final List<WorkerResult> workerResults;

    public HiveResult(String taskId, String report, List<WorkerResult> workerResults) {
        this.taskId = taskId;
        this.report = report;
        this.workerResults = workerResults;
    }

    public String getTaskId() { return taskId; }
    public String getReport() { return report; }
    public List<WorkerResult> getWorkerResults() { return workerResults; }
}
