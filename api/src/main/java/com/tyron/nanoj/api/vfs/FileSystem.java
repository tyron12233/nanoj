package com.tyron.nanoj.api.vfs;

import java.io.IOException;
import java.net.URI;

/**
 * Represents a storage backend (e.g., Local Disk, ZIP file, FTP, Memory).
 */
public interface FileSystem {

    /**
     * @return The scheme this FS handles (e.g., "file", "jar", "memory").
     */
    String getScheme();

    /**
     * Resolves a URI to a FileObject.
     */
    FileObject findResource(URI uri);

    /**
     * Resolves a path string to a FileObject.
     */
    FileObject findResource(String path);

    /**
     * Refresh the view of the filesystem (clear caches, check disk).
     * @param asynchronous If true, refresh happens in background.
     */
    void refresh(boolean asynchronous);

    /**
     * Adds a listener for global changes in this file system.
     */
    void addFileChangeListener(FileChangeListener listener);

    void removeFileChangeListener(FileChangeListener listener);
    
    /**
     * @return True if this file system is read-only (e.g. inside a JAR).
     */
    boolean isReadOnly();
}