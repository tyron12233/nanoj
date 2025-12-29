package com.tyron.nanoj.api.concurrent;

/**
 * Execution context for a scheduled task.
 */
public interface TaskContext {

    CancellationToken cancellation();

    TaskPriority priority();

    long submittedAtNanos();
}
