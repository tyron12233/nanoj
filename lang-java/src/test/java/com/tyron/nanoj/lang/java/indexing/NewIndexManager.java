package com.tyron.nanoj.lang.java.indexing;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.MapDBIndexWrapper;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.core.vfs.FileUtil;
import com.tyron.nanoj.core.vfs.StreamUtils;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class NewIndexManager {

    private final DB db;

    private final Map<String, IndexDefinition<?, ?>> definitions = new ConcurrentHashMap<>();
    private final Map<String, MapDBIndexWrapper> wrappers = new ConcurrentHashMap<>();

    private final BlockingQueue<Map<IndexDefinition<?, ?>, List<BatchResult>>> writeQueue = new ArrayBlockingQueue<>(10);
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Index-Writer-Thread"));
    private final AtomicBoolean isIndexing = new AtomicBoolean(true);

    public NewIndexManager(Project project) {
        File dbFile = new File(project.getCacheDir(), "nanoj_index_new.db");

        this.db = DBMaker.fileDB(dbFile)
                .fileMmapEnable()
                .fileMmapPreclearDisable()
                .cleanerHackEnable()
                .transactionEnable()
                .closeOnJvmShutdown()
                .make();

        startWriter();
    }

    public void register(IndexDefinition<?, ?> def) {
        definitions.put(def.id(), def);
        wrappers.computeIfAbsent(def.id(), id -> new MapDBIndexWrapper(db, id));
    }

    private void startWriter() {
        writerExecutor.submit(() -> {
            while (isIndexing.get() || !writeQueue.isEmpty()) {
                try {
                    var batch = writeQueue.poll(1, TimeUnit.SECONDS);
                    if (batch != null) {
                        flushToDisk(batch);
                        System.out.println("DONE FLUSH, committing");
                        db.commit();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }


    public void processRoots(List<FileObject> roots) {
        isIndexing.set(true);
        try {
            var allFilesStream = roots.stream().flatMap(FileUtil::childrenStream);
            StreamUtils.batch(allFilesStream, 5_000)
                    .parallel()
                    .forEach(this::processBatch);
        } finally {
            finishIndexing();
        }
    }

    private void finishIndexing() {
        isIndexing.set(false);
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(1, TimeUnit.HOURS)) {
                System.err.println("Timed out waiting for index writer to finish.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.commit();
    }

    @SuppressWarnings("unchecked")
    private void processBatch(List<FileObject> batch) {
        Map<IndexDefinition<?, ?>, List<BatchResult>> batchAccumulator = new ConcurrentHashMap<>();

        batch.parallelStream().forEach(file -> {
            Object helper = createHelperForFile(file);
            int fileId = file.getId();

            definitions.values().parallelStream().forEach(def -> {
                if (!def.supports(file)) {
                   return;
                }

                Map<?, ?> rawMap = def.map(file, helper);
                if (rawMap != null && !rawMap.isEmpty()) {
                    Map<Object, Object> data = (Map<Object, Object>) rawMap;
                    batchAccumulator
                            .computeIfAbsent(def, k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(new BatchResult(fileId, file, data));
                }
            });
        });

        if (!batchAccumulator.isEmpty()) {
            try {
                System.out.println("Remaining capacity: " + writeQueue.remainingCapacity());
                writeQueue.put(batchAccumulator);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Object createHelperForFile(FileObject file) {
        if (file.getExtension().equals("class")) {
            try {
                return file.getContent();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <K, V> void flushToDisk(Map<IndexDefinition<?, ?>, List<BatchResult>> accumulator) {
        accumulator.forEach((rawDef, batchResults) -> {
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) rawDef;
            MapDBIndexWrapper wrapper = wrappers.get(def.id());

            List<PreparedWrite> preparedWrites = batchResults.parallelStream()
                    .map(res -> prepareWrite(def, res))
                    .toList();

            preparedWrites.parallelStream().forEach(write -> applyWriteToDb(wrapper, write));
        });
    }

    @SuppressWarnings("unchecked")
    private <K, V> PreparedWrite prepareWrite(IndexDefinition<K, V> def, BatchResult res) {
        int fileId = res.fileId;
        List<SerializedEntry> entries = new ArrayList<>(res.data.size());

        for (Map.Entry<Object, Object> entry : res.data.entrySet()) {
            String keyStr = entry.getKey().toString();
            V value = (V) entry.getValue();

            byte[] valBytes = def.serializeValue(value);
            byte[] packet = new byte[4 + valBytes.length];

            packet[0] = (byte) (fileId >> 24);
            packet[1] = (byte) (fileId >> 16);
            packet[2] = (byte) (fileId >> 8);
            packet[3] = (byte) (fileId);
            System.arraycopy(valBytes, 0, packet, 4, valBytes.length);

            entries.add(new SerializedEntry(keyStr, packet));
        }

        return new PreparedWrite(fileId, entries);
    }

    private void applyWriteToDb(MapDBIndexWrapper wrapper, PreparedWrite write) {
        Set<String> oldKeys = wrapper.getForwardKeys(write.fileId);
        if (oldKeys != null) {
            for (String oldKey : oldKeys) {
                wrapper.removeInvertedByFileId(oldKey, write.fileId);
            }
        }

        Set<String> newKeys = new HashSet<>();
        for (SerializedEntry entry : write.entries) {
            wrapper.putInverted(entry.key, write.fileId, entry.packet);
            newKeys.add(entry.key);
        }

        wrapper.putForward(write.fileId, newKeys);
    }

    private record PreparedWrite(
            int fileId,
            List<SerializedEntry> entries
    ) {}

    private record SerializedEntry(
            String key,
            byte[] packet
    ) {}

    private record BatchResult(
            int fileId,
            FileObject file,
            Map<Object, Object> data
    ) {}
}
