package com.tyron.nanoj.core.indexing;

import org.jetbrains.annotations.NotNull;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.mapdb.serializer.SerializerArray;

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

    // Key (String) -> Array of byte arrays (byte[][])
    // We use byte[][] because MapDB handles it efficiently via Serializer.JAVA
    private final BTreeMap<String, byte[][]> inverted;

    // FileID (Integer) -> Array of Keys (String[])
    private final HTreeMap<Integer, String[]> forward;
    @NotNull
    private final DB db;
    private final String indexId;

    public MapDBIndexWrapper(DB db, String indexId) {
        this.db = db;
        this.indexId = indexId;
        // Inverted Index: B-Tree for sorting/prefix search
        this.inverted = db.treeMap(indexId + "_inv")
                .keySerializer(Serializer.STRING)
                .valueSerializer(new SerializerArray<>(Serializer.BYTE_ARRAY, byte[].class))
                .createOrOpen();

        // Forward Index: HashMap for O(1) lookups by FileID
        this.forward = db.hashMap(indexId + "_fwd")
                .keySerializer(Serializer.INTEGER)
            .valueSerializer(new SerializerArray<>(Serializer.STRING, String.class))// Handles String[]
                .createOrOpen();
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

    private MapDBIndexWrapper(DB db, String indexId, boolean createIfMissing) {
        this.db = db;
        this.indexId = indexId;

        if (createIfMissing) {
            this.inverted = db.treeMap(indexId + "_inv")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(new SerializerArray<>(Serializer.BYTE_ARRAY, byte[].class))
                    .createOrOpen();

            this.forward = db.hashMap(indexId + "_fwd")
                    .keySerializer(Serializer.INTEGER)
                    .valueSerializer(new SerializerArray<>(Serializer.STRING, String.class))
                    .createOrOpen();
        } else {
            this.inverted = db.treeMap(indexId + "_inv")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(new SerializerArray<>(Serializer.BYTE_ARRAY, byte[].class))
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

    public void putInverted(String key, byte[] value) {
        byte[][] existing = inverted.get(key);

        if (existing == null) {
            inverted.put(key, new byte[][]{value});
        } else {
            // Append new value to array
            byte[][] updated = Arrays.copyOf(existing, existing.length + 1);
            updated[existing.length] = value;
            inverted.put(key, updated);
        }
    }

    public void removeInvertedByFileId(String key, int fileIdToRemove) {
        byte[][] existing = inverted.get(key);
        if (existing == null) return;

        List<byte[]> kept = new ArrayList<>();
        boolean changed = false;

        for (byte[] packet : existing) {
            if (packet.length < 4) continue;

            int id = ((packet[0] & 0xFF) << 24) |
                    ((packet[1] & 0xFF) << 16) |
                    ((packet[2] & 0xFF) << 8)  |
                    ((packet[3] & 0xFF));

            if (id == fileIdToRemove) {
                changed = true; // Drop this packet
            } else {
                kept.add(packet);
            }
        }

        if (changed) {
            if (kept.isEmpty()) inverted.remove(key);
            else inverted.put(key, kept.toArray(new byte[0][]));
        }
    }

    /**
     * Removes a value from the inverted index.
     * Since multiple files might map to the same Key, we must match the value byte-for-byte
     * (or allow the caller to handle deserialization, but byte match is fastest here).
     */
    public void removeInverted(String key, byte[] valueToRemove) {
        byte[][] existing = inverted.get(key);
        if (existing == null) return;

        List<byte[]> kept = new ArrayList<>();
        boolean changed = false;

        for (byte[] val : existing) {
            if (Arrays.equals(val, valueToRemove)) {
                changed = true; // Skip this one (remove it)
            } else {
                kept.add(val);
            }
        }

        if (changed) {
            if (kept.isEmpty()) {
                inverted.remove(key);
            } else {
                inverted.put(key, kept.toArray(new byte[0][]));
            }
        }
    }

    /**
     * Exact Match Search
     */
    public List<byte[]> getValues(String key) {
        byte[][] vals = inverted.get(key);
        if (vals == null) return Collections.emptyList();

        List<byte[]> result = new ArrayList<>(vals.length);
        Collections.addAll(result, vals);
        return result;
    }

    /**
     * Prefix Search (e.g., "Li" -> "List", "LinkedList")
     */
    public Map<String, List<byte[]>> searchPrefix(String prefix) {
        // MapDB subMap view: From "prefix" inclusive to "prefix" + MAX_CHAR
        Map<String, byte[][]> view = inverted.subMap(prefix, prefix + Character.MAX_VALUE);

        Map<String, List<byte[]>> results = new HashMap<>();
        for (Map.Entry<String, byte[][]> entry : view.entrySet()) {
            List<byte[]> list = new ArrayList<>();
            Collections.addAll(list, entry.getValue());
            results.put(entry.getKey(), list);
        }
        return results;
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
}