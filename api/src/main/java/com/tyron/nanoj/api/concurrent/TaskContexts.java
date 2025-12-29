package com.tyron.nanoj.api.concurrent;

import java.util.Objects;

/**
 * Access to the current {@link TaskContext} for code running under {@link TaskScheduler}.
 */
public final class TaskContexts {

    private static final ThreadLocal<TaskContext> CURRENT = new ThreadLocal<>();

    private TaskContexts() {
    }

    public static TaskContext currentOrNull() {
        return CURRENT.get();
    }

    public static TaskContext current() {
        TaskContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException("No TaskContext is active on this thread");
        }
        return ctx;
    }

    public static <T> T with(TaskContext ctx, java.util.concurrent.Callable<T> callable) throws Exception {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(callable, "callable");
        TaskContext prev = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return callable.call();
        } finally {
            if (prev == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prev);
            }
        }
    }

    public static void with(TaskContext ctx, Runnable runnable) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(runnable, "runnable");
        TaskContext prev = CURRENT.get();
        CURRENT.set(ctx);
        try {
            runnable.run();
        } finally {
            if (prev == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prev);
            }
        }
    }
}
