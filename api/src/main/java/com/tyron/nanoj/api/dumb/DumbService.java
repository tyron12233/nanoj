package com.tyron.nanoj.api.dumb;

/**
 * IntelliJ-like "dumb mode" support.
 * <p>
 * When the IDE is dumb, indexes are being built/updated and features that depend on them
 * should degrade gracefully (e.g., skip heavy completion providers).
 */
public interface DumbService {

    /**
     * @return true if the IDE is currently in dumb mode.
     */
    boolean isDumb();

    /**
     * Runs immediately if the IDE is smart; otherwise queues until dumb mode ends.
     */
    void runWhenSmart(Runnable runnable);

    /**
     * Marks the IDE as dumb for the lifetime of the returned token.
     * <p>
     * Typical usage:
     * <pre>
     * try (var ignored = dumbService.startDumbTask("Indexing")) {
     *   // do indexing
     * }
     * </pre>
     */
    DumbModeToken startDumbTask(String reason);

    interface DumbModeToken extends AutoCloseable {
        @Override
        void close();
    }
}
