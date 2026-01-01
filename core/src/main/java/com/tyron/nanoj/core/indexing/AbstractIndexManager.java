package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.application.Application;
import com.tyron.nanoj.api.application.ApplicationManager;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.api.indexing.IndexingProgressListener;
import com.tyron.nanoj.api.indexing.IndexingProgressSnapshot;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.core.vfs.FileUtil;
import com.tyron.nanoj.core.vfs.StreamUtils;
import org.jetbrains.annotations.TestOnly;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public abstract class AbstractIndexManager implements IndexManager {

    private static class QueueEntry {
        final Map<IndexDefinition<?, ?>, List<BatchResult>> batch;
        final CountDownLatch flushLatch;

        QueueEntry(Map<IndexDefinition<?, ?>, List<BatchResult>> batch) {
            this.batch = batch;
            this.flushLatch = null;
        }

        QueueEntry(CountDownLatch flushLatch) {
            this.batch = null;
            this.flushLatch = flushLatch;
        }

        boolean isFlushSignal() {
            return flushLatch != null;
        }
    }

    private final DB db;

    protected final IndexingStampStore stampStore;
    protected final Map<String, IndexDefinition<?, ?>> definitions = new ConcurrentHashMap<>();
    protected final Map<String, MapDBIndexWrapper> wrappers = new ConcurrentHashMap<>();

    private final BlockingQueue<QueueEntry> writeQueue = new ArrayBlockingQueue<>(10);
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Index-Writer-Thread"));
    private final AtomicBoolean isIndexing = new AtomicBoolean(true);

    private final CopyOnWriteArrayList<IndexingProgressListener> progressListeners = new CopyOnWriteArrayList<>();

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicLong lastProgressTime = new AtomicLong(0);
    private volatile String currentFilePath = null;

    public AbstractIndexManager() {
        Application application = ApplicationManager.getApplication();

        File dbFile = new File(application.getCacheDirectory(), "nanoj_index_new.db");

        this.db = DBMaker.fileDB(dbFile)
                .fileMmapEnable()
                .fileMmapPreclearDisable()
                .cleanerHackEnable()
                .transactionEnable()
                .closeOnJvmShutdown()
                .make();

        this.stampStore = new IndexingStampStore(db);

        startWriter();
    }

    @Override
    public void register(IndexDefinition<?, ?> def) {
        definitions.put(def.id(), def);
        var wrapper = wrappers.computeIfAbsent(def.id(), id -> new MapDBIndexWrapper(db, id));

        if (stampStore.isIndexDirty(def.id(), def.getVersion())) {
            wrapper.clear();

            stampStore.updateIndexVersion(def.id(), def.getVersion());
        }
    }

    private void startWriter() {
        writerExecutor.submit(() -> {
            while (isIndexing.get() || !writeQueue.isEmpty()) {
                try {
                    QueueEntry entry = writeQueue.poll(1, TimeUnit.SECONDS);

                    if (entry != null) {
                        if (entry.isFlushSignal()) {
                            try {
                                db.commit();
                            } finally {
                                entry.flushLatch.countDown();
                            }
                        } else {
                            try {
                                flushToDisk(entry.batch);
                                db.commit();
                            } catch (Exception e) {
                                db.rollback();
                                System.err.println("Batch write failed, rolled back: " + e.getMessage());
                            }
                        }
                    }

                    notifyProgress();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }

    @Override
    public void processRoots(Iterable<FileObject> roots) {
        isIndexing.set(true);
        processedCount.set(0);
        activeCount.set(0);

        try {
            var allFilesStream = StreamSupport.stream(roots.spliterator(), false)
                    .flatMap(FileUtil::childrenStream);
//                            .filter(file -> !stampStore.isUpToDate(file) || hasAnyPendingIndex(file));

            StreamUtils.batch(allFilesStream, 15_000)
                    .parallel()
                    .forEach(this::processBatch);
        } finally {
            finishIndexing();
        }
    }

    private boolean hasAnyPendingIndex(FileObject file) {
        return definitions.values().stream().filter(it -> it.supports(file))
                .map(IndexDefinition::id)
                .map(wrappers::get)
                .anyMatch(it -> !it.hasIndexed(file.getId()));
    }

    @Override
    public void removeProgressListener(IndexingProgressListener listener) {
        if (listener != null) {
            progressListeners.remove(listener);
        }
    }

    public void addProgressListener(IndexingProgressListener listener) {
        if (listener != null) {
            progressListeners.addIfAbsent(listener);
            try {
                listener.onProgress(getProgressSnapshot());
            } catch (Throwable ignored) {
            }
        }
    }

    public IndexingProgressSnapshot getProgressSnapshot() {
        return new IndexingProgressSnapshot(
                0,
                activeCount.get(),
                processedCount.get(),
                currentFilePath
        );
    }

    /**
     * Throttled notification helper to avoid spamming listeners during fast indexing
     */
    private void notifyProgress() {
        long now = System.currentTimeMillis();
        long last = lastProgressTime.get();
        if (now - last > 100) {
            if (lastProgressTime.compareAndSet(last, now)) {
                IndexingProgressSnapshot snapshot = getProgressSnapshot();
                for (IndexingProgressListener listener : progressListeners) {
                    listener.onProgress(snapshot);
                }
            }
        }
    }

    public void finishIndexing() {
        isIndexing.set(false);
        currentFilePath = null;
        notifyProgress();

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

    public void flush() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            writeQueue.put(new QueueEntry(latch));
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Flush interrupted");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<IndexDefinition<?, ?>, List<BatchResult>> processFile(FileObject file) {
        activeCount.incrementAndGet();
        currentFilePath = file.getName();
        notifyProgress();

        try {
            Object helper = createHelperForFile(file);
            int fileId = file.getId();

            return definitions.values().parallelStream()
                    .filter(def -> def.supports(file))
                    .map(def -> {
                        try {
                            Map<?, ?> map = def.map(file, helper);
                            return new ResultPair(def, map);
                        } catch (Exception e) {
                            System.err.println("Failed to index file " + file.getName() + " for index " + def.id() + ": " + e.getMessage());
                            return new ResultPair(def, null);
                        }
                    }).filter(pair -> pair.data != null && !pair.data.isEmpty())
                    .collect(Collectors.groupingBy(
                            ResultPair::def,
                            Collectors.mapping(
                                    pair -> new BatchResult(fileId, file, (Map<Object, Object>) pair.data),
                                    Collectors.toList()
                            )
                    ));
        } finally {
            activeCount.decrementAndGet();
            processedCount.incrementAndGet();
            notifyProgress();
        }
    }

    private record ResultPair(IndexDefinition<?, ?> def, Map<?, ?> data) {}

    @TestOnly
    public void processBatch(List<FileObject> batch) {
        Map<IndexDefinition<?, ?>, List<BatchResult>> batchAccumulator = batch.parallelStream()
//                .filter(file -> !stampStore.isUpToDate(file) || hasAnyPendingIndex(file))
                .map(this::processFile)
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (list1, list2) -> {
                            List<BatchResult> merged = new ArrayList<>(list1.size() + list2.size());
                            merged.addAll(list1);
                            merged.addAll(list2);
                            return merged;
                        }
                ));

        if (!batchAccumulator.isEmpty()) {
            try {
                System.out.println("Available: " + writeQueue.remainingCapacity());
                writeQueue.put(new QueueEntry(batchAccumulator));
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
            entries.add(new SerializedEntry(keyStr, valBytes));
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

//        stampStore.update(VirtualFileManager.getInstance().findById(write.fileId));
    }

    protected record PreparedWrite(
            int fileId,
            List<SerializedEntry> entries
    ) {}

    protected record SerializedEntry(
            String key,
            byte[] packet
    ) {}

    public record BatchResult(
            int fileId,
            FileObject file,
            Map<Object, Object> data
    ) {}
}