package com.tyron.nanoj.api.concurrent;

import com.tyron.nanoj.api.service.Disposable;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Project-scoped scheduler for potentially expensive tasks (parsing, completion, indexing queries).
 *
 * Threading contract:
 * - Callers must treat submitted tasks as background work (never assume they run on a UI thread).
 * - Implementations should provide cancellation/coalescing so rapid typing does not queue unbounded work.
 */
public interface TaskScheduler extends Disposable {

    @FunctionalInterface
    interface TaskAction<T> {
        T run(TaskContext context) throws Exception;
    }

    /**
     * Submit work to a named lane.
     *
     * Lanes are typically single-threaded when the underlying engine is not thread-safe (e.g. Javac).
     *
     * Latest-only semantics:
     * - For a given {@code (lane, key)} only the latest submitted request is allowed to do real work.
     * - Older queued requests may be cancelled/coalesced.
     * - {@link TaskPriority#USER} may cancel currently running work.
     */
    <T> CompletableFuture<T> submitLatest(String lane, Object key, TaskPriority priority, TaskAction<T> task);

    /**
     * Compatibility overload for callers that don't need a {@link TaskContext}.
     */
    default <T> CompletableFuture<T> submitLatest(String lane, Object key, TaskPriority priority, Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return submitLatest(lane, key, priority, ctx -> task.call());
    }

    /**
     * Cancels any in-flight work for the given {@code (lane, key)} and invalidates queued work.
     */
    void cancel(String lane, Object key);
}
