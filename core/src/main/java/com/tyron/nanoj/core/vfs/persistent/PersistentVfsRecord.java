package com.tyron.nanoj.core.vfs.persistent;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Persisted metadata for a file entry, used as the "snapshot" state.
 */
public final class PersistentVfsRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean folder;
    private final long lastModified;
    private final long length;

    /**
     * Best-effort stable identity for local filesystem entries (inode-like).
     * Null for non-local entries or when unavailable.
     */
    private final String fileKey;

    public PersistentVfsRecord(boolean folder, long lastModified, long length, String fileKey) {
        this.folder = folder;
        this.lastModified = lastModified;
        this.length = length;
        this.fileKey = fileKey;
    }

    public boolean isFolder() {
        return folder;
    }

    public long getLastModified() {
        return lastModified;
    }

    public long getLength() {
        return length;
    }

    public String getFileKey() {
        return fileKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistentVfsRecord that)) return false;
        return folder == that.folder
                && lastModified == that.lastModified
                && length == that.length
                && Objects.equals(fileKey, that.fileKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(folder, lastModified, length, fileKey);
    }
}
