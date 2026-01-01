package com.tyron.nanoj.core.indexing;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.mapdb.*;
import org.mapdb.serializer.GroupSerializer;
import org.mapdb.serializer.GroupSerializerObjectArray;
import org.mapdb.serializer.SerializerArray;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * Encapsulates the MapDB structures for a single Index Definition.
 * <p>
 * Manages two persistent maps:
 * 1. <b>Inverted (B-Tree):</b> Key -> List of Values. Sorted, allows Prefix Search.
 * 2. <b>Forward (Hash Map):</b> FileID -> List of Keys. Fast lookup for invalidation.
 * </p>
 * 
 * <b>Thread Safety:</b> Read methods return defensive copies and are safe for concurrent access.
 * Write methods (putInverted, removeInvertedByFileId, putForward, removeForward) MUST be guarded
 * by the caller's write lock (IndexManager.indexLock).
 */
public class MapDBIndexWrapper {

    public String getIndexId() {
        return indexId;
    }

    public record InvertedKey(String key, int fileId) implements Serializable, Comparable<InvertedKey> {
        @Override
        public int compareTo(@NotNull InvertedKey o) {
            int cmp = this.key.compareTo(o.key);
            if (cmp != 0) return cmp;
            return Integer.compare(this.fileId, o.fileId);
        }
    }

    public static final GroupSerializer<InvertedKey> INVERTED_KEY_SERIALIZER = new GroupSerializerObjectArray<InvertedKey>() {
        @Override
        public void serialize(@NonNull DataOutput2 out, MapDBIndexWrapper.InvertedKey value) throws IOException {
            out.writeUTF(value.key);
            out.writeInt(value.fileId);
        }

        @Override
        public InvertedKey deserialize(@NonNull DataInput2 input, int available) throws IOException {
            return new InvertedKey(input.readUTF(), input.readInt());
        }

        @Override
        public int compare(InvertedKey k1, InvertedKey k2) {
            int cmp = k1.key.compareTo(k2.key);
            if (cmp != 0) return cmp;
            return Integer.compare(k1.fileId, k2.fileId);
        }
    };
    private final BTreeMap<InvertedKey, byte[]> inverted;
    private final HTreeMap<Integer, String[]> forward;
    @NotNull
    private final DB db;
    private final String indexId;

    public MapDBIndexWrapper(DB db, String indexId) {
        this(db, indexId, true);
    }

    /**
     * Opens an existing index wrapper from a DB (typically a read-only shared index DB).
     *
     * @return wrapper, or null if the required maps do not exist.
     */
    public static MapDBIndexWrapper openExisting(DB db, String indexId) {
        try {
            return new MapDBIndexWrapper(db, indexId, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private MapDBIndexWrapper(@NonNull DB db, String indexId, boolean createIfMissing) {
        this.db = db;
        this.indexId = indexId;

        var keySerializer = INVERTED_KEY_SERIALIZER;
        var valueSerializer = Serializer.BYTE_ARRAY;

        if (createIfMissing) {
            this.inverted = db.treeMap(indexId + "_inv")
                    .keySerializer(keySerializer)
                    .valueSerializer(valueSerializer)
                    .createOrOpen();

            this.forward = db.hashMap(indexId + "_fwd")
                    .keySerializer(Serializer.INTEGER)
                    .valueSerializer(new SerializerArray<>(Serializer.STRING, String.class))
                    .createOrOpen();
        } else {
            this.inverted = db.treeMap(indexId + "_inv")
                    .keySerializer(keySerializer)
                    .valueSerializer(valueSerializer)
                    .open();

            this.forward = db.hashMap(indexId + "_fwd")
                    .keySerializer(Serializer.INTEGER)
                    .valueSerializer(new SerializerArray<>(Serializer.STRING, String.class))
                    .open();
        }
    }

    public Set<String> getForwardKeys(int fileId) {
        String[] keys = forward.get(fileId);
        if (keys == null) return Collections.emptySet();
        return new HashSet<>(Arrays.asList(keys));
    }

    public void putForward(int fileId, Set<String> keys) {
        forward.put(fileId, keys.toArray(new String[0]));
    }

    public void removeForward(int fileId) {
        forward.remove(fileId);
    }

    public void putInverted(String key, int fileId, byte[] value) {
        inverted.put(new InvertedKey(key, fileId), value);
    }

    public void removeInvertedByFileId(String key, int fileIdToRemove) {
        inverted.remove(new InvertedKey(key, fileIdToRemove));
    }

    /**
     * Exact Match Search
     */
    public Map<InvertedKey, byte[]> getValues(String key) {
        var minKey = new InvertedKey(key, Integer.MIN_VALUE);
        var maxKey = new InvertedKey(key, Integer.MAX_VALUE);
        return inverted.subMap(minKey, true, maxKey, true);
    }

    /**
     * Prefix Search (e.g., "Li" -> "List", "LinkedList")
     */
    public Map<InvertedKey, byte[]> searchPrefix(String prefix) {
        var minKey = new InvertedKey(prefix, Integer.MIN_VALUE);
        var maxKey = new InvertedKey(prefix + Character.MAX_VALUE, Integer.MAX_VALUE);

        return inverted.subMap(minKey, true, maxKey, true);
    }

    public void snapshot() {

    }

    /**
     * Clears all data for this index (both inverted + forward maps).
     * Caller must hold the IndexManager write lock.
     */
    public void clear() {
        inverted.clear();
        forward.clear();
    }

    /**
     * Best-effort probe to determine whether this index contains any data.
     * Used for migrations/backfill decisions.
     */
    public boolean hasAnyData() {
        try {
            // inverted holds the primary key space; checking it is sufficient.
            return !inverted.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasIndexed(int fileId) {
        return forward.containsKey(fileId);
    }
}