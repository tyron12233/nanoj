package com.tyron.nanoj.api.indexing;

/**
 * Immutable snapshot of indexing queue/progress state.
 */
public final class IndexingProgressSnapshot {
    private final int queuedFiles;
    private final int runningFiles;
    private final long completedFiles;
    private final String currentFilePath;

    public IndexingProgressSnapshot(int queuedFiles, int runningFiles, long completedFiles, String currentFilePath) {
        this.queuedFiles = Math.max(0, queuedFiles);
        this.runningFiles = Math.max(0, runningFiles);
        this.completedFiles = Math.max(0L, completedFiles);
        this.currentFilePath = currentFilePath;
    }

    /** Number of unique files waiting to be indexed. */
    public int getQueuedFiles() {
        return queuedFiles;
    }

    /** Number of files currently being indexed (typically 0 or 1). */
    public int getRunningFiles() {
        return runningFiles;
    }

    /** Monotonic counter of completed per-file indexing runs. */
    public long getCompletedFiles() {
        return completedFiles;
    }

    /** Path of the currently-indexed file, or null if idle. */
    public String getCurrentFilePath() {
        return currentFilePath;
    }
}
