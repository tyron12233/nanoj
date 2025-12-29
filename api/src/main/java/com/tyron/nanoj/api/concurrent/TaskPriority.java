package com.tyron.nanoj.api.concurrent;

/**
 * Scheduling priority for background tasks.
 */
public enum TaskPriority {
    /**
     * Background work (e.g. passive highlighting).
     *
     * Implementations should avoid canceling currently running work, but may coalesce queued requests.
     */
    BACKGROUND,

    /**
     * User-facing work (e.g. code completion, navigation).
     *
     * Implementations should prefer responsiveness and are allowed to cancel in-flight background work.
     */
    USER
}
