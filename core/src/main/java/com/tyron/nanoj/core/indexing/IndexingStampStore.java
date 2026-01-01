package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.vfs.FileObject;
import org.mapdb.DB;
import org.mapdb.Serializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent "was this file already indexed and unchanged?" tracking.
 * <p>
 * Stored in the same MapDB as indices so it survives restarts.
 * <p>
 * Note: today we use (lastModified, length). If we need stronger guarantees,
 * we can extend this to store a content hash (e.g. CRC32/SHA-256) and compute it
 * opportunistically (for example from already-read .class bytes during indexing).
 */
public final class IndexingStampStore {

    private final Map<Integer, Long> indexFileIdToLastModified;
    private final Map<Integer, Long> indexFileIdToLength;

    private final Map<String, Integer> indexIdToVersion;

    IndexingStampStore(DB db) {
        this.indexFileIdToLength = db.hashMap("sys_file_to_length")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.LONG)
                .createOrOpen();

        this.indexFileIdToLastModified = db.hashMap("sys_file_to_last_modified")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.LONG)
                .createOrOpen();

        this.indexIdToVersion = db.hashMap("sys_index_id_to_version")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.INTEGER)
                .createOrOpen();
    }



    public boolean isUpToDate(FileObject file) {
        Long last = indexFileIdToLastModified.get(file.getId());
        Long len = indexFileIdToLength.get(file.getId());

        if (last == null && len == null) {
            return false;
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

    public void update(FileObject file) {
        if (file == null) {
            return;
        }

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

        indexFileIdToLastModified.put(file.getId(), curLast);
        indexFileIdToLength.put(file.getId(), curLen);
    }

    public boolean isIndexDirty(String indexId, int version) {
        Integer storedVersion = indexIdToVersion.get(indexId);
        if (storedVersion == null) {
            return true;
        }

        return storedVersion < version;
    }

    public void updateIndexVersion(String indexId, int version) {
        indexIdToVersion.put(indexId, version);
    }


    public void clear() {
        try {
            indexFileIdToLength.clear();
        } catch (Throwable ignored) {
        }
        try {
            indexFileIdToLastModified.clear();
        } catch (Throwable ignored) {
        }
        try {
            indexIdToVersion.clear();
        } catch (Throwable ignored) {
        }
    }
}
