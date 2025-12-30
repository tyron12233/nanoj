package com.tyron.nanoj.api.vfs;

/**
 * Marker interface for VFS entries that have a stable id.
 * <p>
 * {@link FileObject} extends this interface and provides the default implementation.
 */
public interface FileObjectWithId {

    /**
     * @return stable (best-effort) id for this file.
     */
    int getId();
}
