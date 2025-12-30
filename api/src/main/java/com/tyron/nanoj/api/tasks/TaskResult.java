package com.tyron.nanoj.api.tasks;

public final class TaskResult {

    public enum Status {
        EXECUTED,
        UP_TO_DATE,
        FAILED
    }

    private final String taskId;
    private final Status status;
    private final long startedAtMs;
    private final long finishedAtMs;
    private final Throwable error;

    private TaskResult(String taskId, Status status, long startedAtMs, long finishedAtMs, Throwable error) {
        this.taskId = taskId;
        this.status = status;
        this.startedAtMs = startedAtMs;
        this.finishedAtMs = finishedAtMs;
        this.error = error;
    }

    public static TaskResult executed(String taskId, long startedAtMs, long finishedAtMs) {
        return new TaskResult(taskId, Status.EXECUTED, startedAtMs, finishedAtMs, null);
    }

    public static TaskResult upToDate(String taskId, long startedAtMs, long finishedAtMs) {
        return new TaskResult(taskId, Status.UP_TO_DATE, startedAtMs, finishedAtMs, null);
    }

    public static TaskResult failed(String taskId, long startedAtMs, long finishedAtMs, Throwable error) {
        return new TaskResult(taskId, Status.FAILED, startedAtMs, finishedAtMs, error);
    }

    public String getTaskId() {
        return taskId;
    }

    public Status getStatus() {
        return status;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public long getFinishedAtMs() {
        return finishedAtMs;
    }

    public Throwable getError() {
        return error;
    }
}
