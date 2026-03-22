package com.hiveforge.worker;

public class WorkerResult {

    private final String workerId;
    private final String workerName;
    private final boolean success;
    private final String output;
    private final String error;

    private WorkerResult(String workerId, String workerName, boolean success, String output, String error) {
        this.workerId = workerId;
        this.workerName = workerName;
        this.success = success;
        this.output = output;
        this.error = error;
    }

    public static WorkerResult success(String workerId, String workerName, String output) {
        return new WorkerResult(workerId, workerName, true, output, null);
    }

    public static WorkerResult failure(String workerId, String workerName, String error) {
        return new WorkerResult(workerId, workerName, false, null, error);
    }

    /** 兼容无 workerId 的调用 */
    public static WorkerResult success(String workerName, String output) {
        return new WorkerResult(null, workerName, true, output, null);
    }

    /** 兼容无 workerId 的调用（如超时、DAG 未找到 worker 等场景） */
    public static WorkerResult failure(String workerName, String error) {
        return new WorkerResult(null, workerName, false, null, error);
    }

    public String getWorkerId() { return workerId; }
    public String getWorkerName() { return workerName; }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
}
