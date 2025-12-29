package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.vfs.FileObject;
import org.mapdb.DB;
import org.mapdb.Serializer;

import java.util.Map;

/**
 * Persistent "was this file already indexed and unchanged?" tracking.
 *
 * Stored in the same MapDB as indices so it survives restarts.
 *
 * Note: today we use (lastModified, length). If we need stronger guarantees,
 * we can extend this to store a content hash (e.g. CRC32/SHA-256) and compute it
 * opportunistically (for example from already-read .class bytes during indexing).
 */
public final class IndexingStampStore {

    private final Map<String, Long> indexPathToLastModified;
    private final Map<String, Long> indexPathToLength;
    private final Map<String, Integer> indexPathToVersion;

    IndexingStampStore(DB db) {
        this.indexPathToLastModified = db.hashMap("sys_index_path_last_modified", Serializer.STRING, Serializer.LONG).createOrOpen();
        this.indexPathToLength = db.hashMap("sys_index_path_length", Serializer.STRING, Serializer.LONG).createOrOpen();
        this.indexPathToVersion = db.hashMap("sys_index_path_version", Serializer.STRING, Serializer.INTEGER).createOrOpen();
    }

    public boolean isUpToDate(String indexId, FileObject file) {
        return isUpToDate(indexId, -1, file);
    }

    /**
     * Version-aware stamp check.
     *
     * @param expectedVersion if negative, version is not checked.
     */
    public boolean isUpToDate(String indexId, int expectedVersion, FileObject file) {
        if (indexId == null || indexId.isBlank() || file == null || file.isFolder()) {
            return false;
        }

        String path;
        try {
            path = file.getPath();
        } catch (Throwable t) {
            return false;
        }

        return isUpToDate(indexId, expectedVersion, path, file);
    }

    public boolean isUpToDate(String indexId, String path, FileObject file) {
        return isUpToDate(indexId, -1, path, file);
    }

    public boolean isUpToDate(String indexId, int expectedVersion, String path, FileObject file) {
        if (indexId == null || indexId.isBlank() || path == null || path.isBlank() || file == null || file.isFolder()) {
            return false;
        }

        String key = key(indexId, path);

        Long last = indexPathToLastModified.get(key);
        Long len = indexPathToLength.get(key);
        Integer ver = indexPathToVersion.get(key);
        if (last == null || len == null) {
            return false;
        }
        if (expectedVersion >= 0) {
            if (ver == null || ver != expectedVersion) {
                return false;
            }
        }

        long curLast;
        long curLen;
        try {
            curLast = file.lastModified();
        } catch (Throwable t) {
            return false;
        }
        try {
            curLen = file.getLength();
        } catch (Throwable t) {
            return false;
        }

        return last == curLast && len == curLen;
    }

    public void update(String indexId, FileObject file) {
        update(indexId, -1, file);
    }

    public void update(String indexId, int version, FileObject file) {
        if (indexId == null || indexId.isBlank() || file == null || file.isFolder()) {
            return;
        }

        String path;
        try {
            path = file.getPath();
        } catch (Throwable t) {
            return;
        }

        update(indexId, version, path, file);
    }

    public void update(String indexId, String path, FileObject file) {
        update(indexId, -1, path, file);
    }

    public void update(String indexId, int version, String path, FileObject file) {
        if (indexId == null || indexId.isBlank() || path == null || path.isBlank() || file == null || file.isFolder()) {
            return;
        }

        String key = key(indexId, path);

        long curLast;
        long curLen;
        try {
            curLast = file.lastModified();
        } catch (Throwable t) {
            return;
        }
        try {
            curLen = file.getLength();
        } catch (Throwable t) {
            return;
        }

        indexPathToLastModified.put(key, curLast);
        indexPathToLength.put(key, curLen);
        if (version >= 0) {
            indexPathToVersion.put(key, version);
        }
    }

    private static String key(String indexId, String path) {
        // Keep it compact and stable; NUL cannot appear in normal URI paths.
        return indexId + "\u0000" + path;
    }
}
