package com.hiveforge.worker;

public class WorkerResult {

    private final String workerName;
    private final boolean success;
    private final String output;
    private final String error;

    private WorkerResult(String workerName, boolean success, String output, String error) {
        this.workerName = workerName;
        this.success = success;
        this.output = output;
        this.error = error;
    }

    public static WorkerResult success(String workerName, String output) {
        return new WorkerResult(workerName, true, output, null);
    }

    public static WorkerResult failure(String workerName, String error) {
        return new WorkerResult(workerName, false, null, error);
    }

    public String getWorkerName() { return workerName; }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
}
