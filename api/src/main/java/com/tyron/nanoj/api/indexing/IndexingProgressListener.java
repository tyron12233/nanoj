package com.tyron.nanoj.api.indexing;

/**
 * Listener for indexing queue/progress updates.
 */
public interface IndexingProgressListener {
    void onProgress(IndexingProgressSnapshot snapshot);
}
