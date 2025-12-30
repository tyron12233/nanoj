package com.tyron.nanoj.api.tasks;

/**
 * Listener for task execution events.
 *
 * <p>Listeners are notified for every task that is evaluated during a run,
 * including {@link TaskResult.Status#UP_TO_DATE} tasks.
 */
public interface TaskExecutionListener {

    /** Called right before a task is evaluated/executed. */
    default void onTaskStarted(Task task) {
    }

    /** Called after a task produced a {@link TaskResult}. */
    default void onTaskFinished(Task task, TaskResult result) {
    }
}
