package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import org.mapdb.Atomic;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds a standalone MapDB index file that can be mounted read-only as a "shared index".
 *
 * The output file uses the same schema as {@link com.tyron.nanoj.api.indexing.IndexManager}:
 * - sys_path_to_id, sys_id_to_path, sys_seq_file_id
 * - {indexId}_inv and {indexId}_fwd maps for each IndexDefinition
 */
public final class SharedIndexBuilder {

    private static final Logger LOG = Logger.getLogger(SharedIndexBuilder.class.getName());

    private SharedIndexBuilder() {
    }

    public static void build(File outFile, Iterable<FileObject> files, Iterable<IndexDefinition<?, ?>> definitions) {
        if (outFile == null) {
            throw new IllegalArgumentException("outFile == null");
        }

        long t0 = System.nanoTime();
        if (LOG.isLoggable(Level.INFO)) {
            LOG.info("Building shared index DB: " + outFile);
        }

        DB db = DBMaker.fileDB(outFile)
                .fileMmapEnable()
                .fileMmapPreclearDisable()
                .cleanerHackEnable()
                .transactionEnable()
                .closeOnJvmShutdown()
                .make();

        try {
            Map<String, Integer> pathToId = db.hashMap("sys_path_to_id", Serializer.STRING, Serializer.INTEGER).createOrOpen();
            Map<Integer, String> idToPath = db.hashMap("sys_id_to_path", Serializer.INTEGER, Serializer.STRING).createOrOpen();
            Atomic.Integer nextFileId = db.atomicInteger("sys_seq_file_id", 1).createOrOpen();

            Map<String, IndexDefinition<?, ?>> defsById = new HashMap<>();
            Map<String, MapDBIndexWrapper> wrappers = new HashMap<>();
            for (IndexDefinition<?, ?> def : definitions) {
                if (def == null) continue;
                defsById.put(def.id(), def);
                wrappers.put(def.id(), new MapDBIndexWrapper(db, def.id()));
            }

            Map<String, LongAdder> keysByIndex = new ConcurrentHashMap<>();
            for (String id : defsById.keySet()) {
                keysByIndex.put(id, new LongAdder());
            }

            LongAdder filesVisited = new LongAdder();
            LongAdder filesIndexed = new LongAdder();

            if (files != null) {
                for (FileObject file : files) {
                    if (file == null) continue;

                    filesVisited.increment();

                    int fileId = getOrCreateId(pathToId, idToPath, nextFileId, file.getPath());
                    Object helper = buildHelper(file);

                    for (IndexDefinition<?, ?> def : defsById.values()) {
                        try {
                            if (!def.supports(file)) continue;
                            int added = updateIndex(wrappers.get(def.id()), def, file, fileId, helper);
                            if (added > 0) {
                                keysByIndex.get(def.id()).add(added);
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    filesIndexed.increment();
                    if (LOG.isLoggable(Level.FINE) && (filesIndexed.longValue() % 1000L == 0L)) {
                        LOG.fine("Shared index build progress: filesIndexed=" + filesIndexed.longValue());
                    }
                }
            }

            db.commit();

            if (LOG.isLoggable(Level.INFO)) {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                LOG.info("Shared index build done: filesVisited=" + filesVisited.longValue()
                        + ", filesIndexed=" + filesIndexed.longValue()
                        + ", indices=" + defsById.size()
                        + ", took=" + ms + "ms");

                if (LOG.isLoggable(Level.FINE)) {
                    for (Map.Entry<String, LongAdder> e : keysByIndex.entrySet()) {
                        LOG.fine("Shared index stats: indexId='" + e.getKey() + "' keysAdded=" + e.getValue().longValue());
                    }
                }
            }

        } finally {
            if (!db.isClosed()) {
                try {
                    db.commit();
                } catch (Throwable ignored) {
                }
                db.close();
            }
        }
    }

    private static int getOrCreateId(Map<String, Integer> pathToId,
                                    Map<Integer, String> idToPath,
                                    Atomic.Integer nextFileId,
                                    String path) {
        Integer id = pathToId.get(path);
        if (id != null) return id;

        int newId = nextFileId.incrementAndGet();
        pathToId.put(path, newId);
        idToPath.put(newId, path);
        return newId;
    }

    private static Object buildHelper(FileObject file) {
        try {
            if (file != null && "class".equalsIgnoreCase(file.getExtension())) {
                return file.getContent();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> int updateIndex(MapDBIndexWrapper wrapper,
                                           IndexDefinition<K, V> def,
                                           FileObject file,
                                           int fileId,
                                           Object helper) {
        if (wrapper == null || def == null) return 0;

        Map<K, V> newEntries;
        try {
            newEntries = def.map(file, helper);
        } catch (Throwable t) {
            newEntries = Collections.emptyMap();
        }
        if (newEntries == null) newEntries = Collections.emptyMap();

        int keysAdded = 0;

        Set<String> oldKeys = wrapper.getForwardKeys(fileId);
        for (String oldKey : oldKeys) {
            wrapper.removeInvertedByFileId(oldKey, fileId);
        }

        Set<String> newKeysForForward = new HashSet<>();
        for (Map.Entry<K, V> entry : newEntries.entrySet()) {
            String keyStr = entry.getKey().toString();
            byte[] valBytes = def.serializeValue(entry.getValue());
            wrapper.putInverted(keyStr, fileId, valBytes);
            newKeysForForward.add(keyStr);
            keysAdded++;
        }

        wrapper.putForward(fileId, newKeysForForward);
        return keysAdded;
    }
}
