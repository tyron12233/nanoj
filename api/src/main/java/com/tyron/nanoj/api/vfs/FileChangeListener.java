package com.tyron.nanoj.api.vfs;

/**
 * Observer for file system changes.
 * Essential for the Indexer to know when to re-index.
 */
public interface FileChangeListener {
    void fileCreated(FileEvent event);
    void fileDeleted(FileEvent event);
    void fileChanged(FileEvent event);
    void fileRenamed(FileEvent event);
}