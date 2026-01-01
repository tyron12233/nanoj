package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.indexing.IndexingProgressListener;
import com.tyron.nanoj.api.indexing.IndexingProgressSnapshot;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.vfs.*;
import com.tyron.nanoj.core.dumb.DumbCore;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central coordinator for persistent indexing using MapDB.
 * <p>
 * <b>Features:</b>
 * <ul>
 *     <li>ACID Transactions (atomic commit of all indices)</li>
 *     <li>Off-Heap Storage (avoids OOM on Android)</li>
 *     <li>Uses {@link FileObject#getId()} from the VFS for File IDs</li>
 * </ul>
 */
@Deprecated
public class IndexManager implements Disposable {

    private static final Logger LOG = Logger.getLogger(IndexManager.class.getName());

    /**
     * If an indexing task takes longer than this threshold, the project enters dumb mode.
     * Defaults to 750ms.
     */
    public static final String DUMB_THRESHOLD_MS_KEY = "nanoj.indexing.dumbThresholdMs";

    /**
     * Comma/semicolon separated list of absolute paths to read-only shared index DB files.
     * These DBs are mounted for reads (completion/search) and are never written to.
     */
    public static final String SHARED_INDEX_PATHS_KEY = "nanoj.indexing.sharedIndexPaths";

    /**
     * Parallelism for batch indexing precompute (helper building + IndexDefinition.map + value serialization).
     * Writes are still applied on the single writer thread/transaction.
     * Set to 1 to disable.
     */
    public static final String PRECOMPUTE_PARALLELISM_KEY = "nanoj.indexing.precomputeParallelism";

    /**
     * Batch size for traversing folder/jar/jrt roots.
     */
    public static final String TRAVERSAL_BATCH_SIZE_KEY = "nanoj.indexing.traversalBatchSize";

    /**
     * If true, JRT indexing skips packages that are not exported from their module.
     * These packages are not accessible from normal source code (unnamed module) and
     * indexing them is mostly noise + wasted work.
     */
    public static final String JRT_SKIP_NON_EXPORTED_KEY = "nanoj.indexing.jrt.skipNonExported";

    /**
     * If true, files that were already indexed and haven't changed (lastModified/length)
     * are skipped.
     */
    public static final String SKIP_UNCHANGED_FILES_KEY = "nanoj.indexing.skipUnchangedFiles";

    /**
        * Deprecated: definition backfill input collection lives in {@link IndexingInputCollector}.
        * This key is intentionally unused by {@link IndexManager}.
        */
        @Deprecated
        public static final String SCHEMA_BACKFILL_BATCH_SIZE_KEY = "nanoj.indexing.schemaBackfillBatchSize";

    public static IndexManager getInstance(Project project) {
        return ProjectServiceManager.getService(project, IndexManager.class);
    }

    private final DB db;
    private final List<SharedIndexStore> sharedStores = new CopyOnWriteArrayList<>();
    private final ExecutorService writeExecutor;
    private final ExecutorService scanExecutor;
    private final ExecutorService precomputeExecutor;
    private final ScheduledExecutorService dumbScheduler;

    private final int traversalBatchSize;
    private final int precomputeParallelism;
    private final boolean skipNonExportedJrt;

    private final boolean skipUnchangedFiles;
    private final IndexingStampStore stampStore;
    private final IndexDefinitionVersionStore definitionVersionStore;

    private final Map<String, PendingIndexRequest> pendingByPath = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<IndexingProgressListener> progressListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger queuedFiles = new AtomicInteger(0);
    private final AtomicInteger runningFiles = new AtomicInteger(0);
    private final AtomicLong completedFileRuns = new AtomicLong(0L);
    private final AtomicLong enqueueOrderSeq = new AtomicLong(0L);
    private final AtomicReference<String> currentFilePath = new AtomicReference<>(null);

    private final FileChangeListener vfsListener;
    private final AtomicBoolean vfsListenerAttached = new AtomicBoolean(false);

    private final AtomicBoolean disposed = new AtomicBoolean(false);


    private final Project project;

    private final long dumbThresholdMs;

    // index Registries
    private final Map<String, IndexDefinition<?, ?>> definitions = new ConcurrentHashMap<>();
    private final Map<String, MapDBIndexWrapper> wrappers = new ConcurrentHashMap<>();

    // rhread-safe access: readers (completion) acquire read lock; writers (indexing) acquire write lock
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    private static final class SharedIndexStore {
        final File file;
        final DB db;
        final Map<Integer, String> idToPath;
        final Map<String, MapDBIndexWrapper> wrappers = new ConcurrentHashMap<>();

        SharedIndexStore(File file, DB db, Map<Integer, String> idToPath) {
            this.file = file;
            this.db = db;
            this.idToPath = idToPath;
        }
    }

    private static final class PendingIndexRequest {
        final String path;
        final AtomicReference<FileObject> fileRef;
        final CompletableFuture<Void> future;
        final AtomicLong version = new AtomicLong(0L);
        final AtomicLong order = new AtomicLong(0L);

        PendingIndexRequest(String path, FileObject initialFile, long order) {
            this.path = path;
            this.fileRef = new AtomicReference<>(initialFile);
            this.future = new CompletableFuture<>();
            this.order.set(order);
            this.version.set(1L);
        }

        void update(FileObject file, long order) {
            this.fileRef.set(file);
            this.order.set(order);
            this.version.incrementAndGet();
        }
    }

    public IndexManager(Project project) {
        this.project = project;

        // ensure dumb mode infrastructure is available even if callers didn't run any "Core.register()" helper.
        DumbCore.register(project);

        this.dumbThresholdMs = readLongConfig(project, DUMB_THRESHOLD_MS_KEY, 750L);

        this.traversalBatchSize = (int) Math.max(128, readLongConfig(project, TRAVERSAL_BATCH_SIZE_KEY, 10_000L));

        int defaultParallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.precomputeParallelism = (int) Math.max(1, readLongConfig(project, PRECOMPUTE_PARALLELISM_KEY, defaultParallelism));

        this.skipNonExportedJrt = readBooleanConfig(project, JRT_SKIP_NON_EXPORTED_KEY, true);
        this.skipUnchangedFiles = readBooleanConfig(project, SKIP_UNCHANGED_FILES_KEY, true);

        File dbFile = new File(project.getCacheDir(), "nanoj_index.db");

        this.db = DBMaker.fileDB(dbFile)
                .fileMmapEnable()            // use memory-mapped files
                .fileMmapPreclearDisable()   // performance tweak
                .cleanerHackEnable()         // fix JVM bug with unmapping
                .transactionEnable()         // enable rollback/commit
                .closeOnJvmShutdown()
                .make();

        this.stampStore = new IndexingStampStore(db);
        this.definitionVersionStore = new IndexDefinitionVersionStore(db);

        loadSharedIndexesFromConfig();

        this.writeExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Index-Writer"));

        this.scanExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Index-Scan");
            t.setDaemon(true);
            return t;
        });

        this.precomputeExecutor = (precomputeParallelism <= 1) ? null : Executors.newFixedThreadPool(precomputeParallelism, r -> {
            Thread t = new Thread(r, "Index-Precompute");
            t.setDaemon(true);
            return t;
        });

        this.dumbScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DumbMode-Indexing-Timer");
            t.setDaemon(true);
            return t;
        });

        this.vfsListener = new FileChangeListener() {
            @Override
            public void fileCreated(FileEvent event) {
                updateFile(event.getFile());
            }

            @Override
            public void fileDeleted(FileEvent event) {
                // VFS knows about deletes (including those detected by refresh diff). Remove index data.
                removeFileAsync(event.getFile());
            }

            @Override
            public void fileChanged(FileEvent event) {
                updateFile(event.getFile());
            }

            @Override
            public void fileRenamed(FileEvent event) {
                FileObject newFile = event.getFile();
                FileObject oldFile = (event instanceof FileRenameEvent re) ? re.getOldFile() : null;

                int newId = safeVfsId(newFile);
                int oldId = safeVfsId(oldFile);

                // If the VFS provides stable ids across rename, updating the new file is sufficient.
                // If ids differ (best-effort), also remove stale data for the old id.
                if (oldId > 0 && newId > 0 && oldId != newId) {
                    removeFileAsync(oldFile);
                }

                updateFile(newFile);
            }
        };
    }

    /**
     * Called by the platform when the project is fully opened.
     * This is where indexing begins (VFS listening, initial scans, etc.).
     */
    public void onProjectOpened() {
        if (disposed.get() || !project.isOpen()) {
            return;
        }

        if (vfsListenerAttached.compareAndSet(false, true)) {
            VirtualFileManager.getInstance().addGlobalListener(vfsListener);
        }
    }

    public void register(IndexDefinition<?, ?> def) {
        if (def == null) {
            return;
        }

        definitions.put(def.id(), def);
        MapDBIndexWrapper wrapper = wrappers.computeIfAbsent(def.id(), id -> new MapDBIndexWrapper(db, id));

        try {
            Integer prev = definitionVersionStore.getStoredVersion(def.id());
            if (prev == null || prev != def.getVersion()) {
                definitionVersionStore.putVersion(def.id(), def.getVersion());

                // ensure queries can't see stale packets after a schema/code change.
                indexLock.writeLock().lock();
                try {
                    wrapper.clear();
                    db.commit();
                } finally {
                    indexLock.writeLock().unlock();
                }
            }
        } catch (Throwable ignored) {
        }

        // best-effort: if a shared DB does not have this indexId, we just skip it.
        for (SharedIndexStore store : sharedStores) {
            try {
                MapDBIndexWrapper w = MapDBIndexWrapper.openExisting(store.db, def.id());
                if (w != null) {
                    store.wrappers.put(def.id(), w);
                } else {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("Shared index missing maps for indexId='" + def.id() + "' in " + store.file);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Mounts a read-only shared index DB for queries (completion/search).
     * <p>
     * This can be called at any time:
     * - If called before {@link #register(IndexDefinition)}, wrappers will be attached when definitions are registered.
     * - If called after registration, wrappers are opened best-effort for all currently registered definitions.
     */
    public void mountSharedIndex(File sharedIndexFile) {
        if (sharedIndexFile == null) {
            throw new IllegalArgumentException("sharedIndexFile == null");
        }
        if (!sharedIndexFile.isFile()) {
            return;
        }
        if (disposed.get() || !project.isOpen()) {
            return;
        }

        for (SharedIndexStore store : sharedStores) {
            if (store != null && sharedIndexFile.equals(store.file)) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("Shared index already mounted: " + sharedIndexFile);
                }
                return;
            }
        }

        long t0 = System.nanoTime();
        try {
            DB sharedDb = DBMaker.fileDB(sharedIndexFile)
                    .fileMmapEnable()
                    .fileMmapPreclearDisable()
                    .cleanerHackEnable()
                    .readOnly()
                    .closeOnJvmShutdown()
                    .make();

            // shared DB must contain the id->path registry to resolve search results.
            Map<Integer, String> sharedIdToPath = sharedDb
                    .hashMap("sys_id_to_path", Serializer.INTEGER, Serializer.STRING)
                    .open();

            SharedIndexStore store = new SharedIndexStore(sharedIndexFile, sharedDb, sharedIdToPath);
            sharedStores.add(store);

            if (LOG.isLoggable(Level.INFO)) {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                LOG.info("Mounted shared index: " + sharedIndexFile + " (" + ms + "ms)");
            }

            // try to open wrappers for already-registered defs.
            int attached = 0;
            for (IndexDefinition<?, ?> def : definitions.values()) {
                try {
                    MapDBIndexWrapper w = MapDBIndexWrapper.openExisting(sharedDb, def.id());
                    if (w != null) {
                        store.wrappers.put(def.id(), w);
                        attached++;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (LOG.isLoggable(Level.INFO)) {
                LOG.info("Shared index wrappers attached=" + attached + ", registeredDefinitions=" + definitions.size());
            }
        } catch (Throwable ignore) {
            // best-effort: ignore invalid/mismatched shared index DBs.
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Failed to mount shared index: " + sharedIndexFile, ignore);
            }
        }
    }

    private void loadSharedIndexesFromConfig() {
        String raw;
        try {
            raw = project.getConfiguration().getProperty(SHARED_INDEX_PATHS_KEY);
        } catch (Throwable t) {
            raw = null;
        }

        if (raw == null || raw.isBlank()) {
            return;
        }

        if (LOG.isLoggable(Level.INFO)) {
            LOG.info("Loading shared indexes from config key '" + SHARED_INDEX_PATHS_KEY + "': " + raw);
        }

        String[] parts = raw.split("[;,]");
        for (String p : parts) {
            if (p == null) continue;
            String path = p.trim();
            if (path.isEmpty()) continue;

            File f = new File(path);
            if (!f.isFile()) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("Shared index path is not a file: " + f);
                }
                continue;
            }

            mountSharedIndex(f);
        }
    }

    private boolean hasSharedStores() {
        return !sharedStores.isEmpty();
    }

    private boolean isSharedFileId(int globalFileId) {
        return (globalFileId & 0x8000_0000) != 0;
    }

    private int encodeSharedFileId(int storeOrdinal, int sharedFileId) {
        // 1 bit marker + 7 bits store + 24 bits fileId
        return 0x8000_0000 | ((storeOrdinal & 0x7F) << 24) | (sharedFileId & 0x00FF_FFFF);
    }

    private int decodeSharedStoreOrdinal(int globalFileId) {
        return (globalFileId >>> 24) & 0x7F;
    }

    private int decodeSharedLocalId(int globalFileId) {
        return globalFileId & 0x00FF_FFFF;
    }

    public String getFilePath(int id) {
        if (isSharedFileId(id)) {
            int storeOrdinal = decodeSharedStoreOrdinal(id);
            int sharedLocalId = decodeSharedLocalId(id);
            if (storeOrdinal >= 0 && storeOrdinal < sharedStores.size()) {
                try {
                    return sharedStores.get(storeOrdinal).idToPath.get(sharedLocalId);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
        try {
            FileObject fo = VirtualFileManager.getInstance().findById(id);
            return fo != null ? fo.getPath() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Snapshot of already-known (id-registered) file paths.
     * <p>
     * Intended for indexing input collection (e.g. when a new index definition is registered).
     */
    List<String> getKnownFilePathsSnapshot() {
        return null;
    }

    public void updateFile(FileObject file) {
        if (disposed.get() || !project.isOpen()) {
            return;
        }

        // Containers/roots (folders, jar roots, local .jar files) must be traversed even if
        // the container itself is not supported by any IndexDefinition.
        try {
            Future<?> traversal = maybeIndexRecursively(file);
            if (traversal != null) {
                return;
            }
        } catch (Throwable ignored) {
        }

        // avoid doing any MapDB work for file types that no index definition supports.
        // this is important for classpath indexing attempts that may touch many .jar files.
        if (!shouldIndex(file)) {
            return;
        }

        if (skipUnchangedFiles && !isOutdatedForAnyIndex(file)) {
            return;
        }

        updateFileAsync(file);
    }

    public void addProgressListener(IndexingProgressListener listener) {
        if (listener != null) {
            progressListeners.addIfAbsent(listener);
            // Emit an initial snapshot for convenience.
            try {
                listener.onProgress(getProgressSnapshot());
            } catch (Throwable ignored) {
            }
        }
    }

    public void removeProgressListener(IndexingProgressListener listener) {
        if (listener != null) {
            progressListeners.remove(listener);
        }
    }

    /**
     * Clears the local index DB content (all index maps + stamps) and resets in-memory queues.
     * <p>
     * This does not affect mounted shared indexes.
     */
    public Future<?> invalidateLocalCachesAsync() {
        if (disposed.get() || !project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            writeExecutor.submit(() -> {
                if (disposed.get() || !project.isOpen()) {
                    future.complete(null);
                    return;
                }

                // Drop queued work (best-effort) and clear progress.
                pendingByPath.clear();
                queuedFiles.set(0);
                runningFiles.set(0);
                completedFileRuns.set(0L);
                currentFilePath.set(null);
                fireProgressChanged();

                indexLock.writeLock().lock();
                try {
                    for (MapDBIndexWrapper w : wrappers.values()) {
                        try {
                            if (w != null) w.clear();
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        stampStore.clear();
                    } catch (Throwable ignored) {
                    }
                    try {
                        definitionVersionStore.clear();
                    } catch (Throwable ignored) {
                    }
                    try {
                        db.commit();
                    } catch (Throwable ignored) {
                    }
                } finally {
                    indexLock.writeLock().unlock();
                }

                future.complete(null);
                fireProgressChanged();
            });
        } catch (RejectedExecutionException e) {
            future.complete(null);
        }

        return future;
    }

    public IndexingProgressSnapshot getProgressSnapshot() {
        return new IndexingProgressSnapshot(
                queuedFiles.get(),
                runningFiles.get(),
                completedFileRuns.get(),
                currentFilePath.get()
        );
    }

    /**
     * Returns a best-effort snapshot of queued file paths (excludes the currently-indexed file).
     */
    public List<String> getQueuedFilePaths(int limit) {
        String current = currentFilePath.get();

        List<PendingIndexRequest> pending = new ArrayList<>(pendingByPath.values());
        pending.sort(Comparator.comparingLong(r -> r.order.get()));

        List<String> result = new ArrayList<>();
        for (PendingIndexRequest r : pending) {
            if (result.size() >= limit) {
                break;
            }
            if (current != null && current.equals(r.path)) {
                continue;
            }
            result.add(r.path);
        }
        return result;
    }

    private boolean shouldIndex(FileObject file) {
        if (!JrtExportFilter.isIndexable(file, skipNonExportedJrt)) {
            return false;
        }
        for (IndexDefinition<?, ?> def : definitions.values()) {
            try {
                if (def.supports(file)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean isOutdatedForAnyIndex(FileObject file) {
        if (!skipUnchangedFiles) {
            return true;
        }
        if (file == null || file.isFolder()) {
            return false;
        }

        indexLock.readLock().lock();
        try {
            for (IndexDefinition<?, ?> def : definitions.values()) {
                if (def == null) {
                    continue;
                }
                try {
                    if (!def.supports(file)) {
                        continue;
                    }
//                    if (def.isOutdated(file, stampStore)) {
//                        return true;
//                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    private static void waitQuietly(Future<?> f) {
        if (f == null) return;
        try {
            f.get();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Enqueues an index update on the background writer.
     * <p>
     * This never blocks the caller thread.
     *
     * @return a Future that can be waited on (tests / callers that need determinism).
     */
    public Future<?> updateFileAsync(FileObject file) {
        if (disposed.get() || !project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }

        if (file == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Traverse container roots first (folders, jar roots, local .jar files).
        Future<?> traversal = maybeIndexRecursively(file);
        if (traversal != null) {
            return traversal;
        }

        if (!file.isFolder() && skipUnchangedFiles && !isOutdatedForAnyIndex(file)) {
            return CompletableFuture.completedFuture(null);
        }

        if (!shouldIndex(file)) {
            return CompletableFuture.completedFuture(null);
        }

        String path = file.getPath();
        long order = enqueueOrderSeq.incrementAndGet();

        PendingIndexRequest existing = pendingByPath.get(path);
        if (existing != null) {
            existing.update(file, order);
            fireProgressChanged();
            return existing.future;
        }

        PendingIndexRequest created = new PendingIndexRequest(path, file, order);
        PendingIndexRequest prev = pendingByPath.putIfAbsent(path, created);
        if (prev != null) {
            prev.update(file, order);
            fireProgressChanged();
            return prev.future;
        }

        queuedFiles.incrementAndGet();
        fireProgressChanged();
        submitIndexTask(created);
        return created.future;
    }

    /**
     * Batch variant of {@link #updateFileAsync(FileObject)}.
     * <p>
     * This is intended for classpath scans (e.g. thousands of .class files from {@code jrt:} / {@code jar:})
     * where per-file task scheduling and per-file MapDB commits become the bottleneck.
     * <p>
     * Each file is still indexed with its own FileID (so multivalued keys like ShortClassNameIndex remain correct),
     * but work is performed in a single writer task and a single MapDB commit.
     */
    public Future<?> updateFilesAsync(Iterable<FileObject> files) {
        if (disposed.get() || !project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }

        if (files == null) {
            return CompletableFuture.completedFuture(null);
        }

        ArrayList<FileObject> indexables = new ArrayList<>();
        ArrayList<Future<?>> traversalFutures = new ArrayList<>();
        for (FileObject f : files) {
            if (f == null) continue;

            Future<?> traversal = maybeIndexRecursively(f);
            if (traversal != null) {
                traversalFutures.add(traversal);
                continue;
            }

            if (!shouldIndex(f)) continue;
            if (skipUnchangedFiles && !isOutdatedForAnyIndex(f)) continue;
            indexables.add(f);
        }

        CompletableFuture<Void> leafFuture = indexables.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : asCompletableVoid(updateFilesAsyncLeaf(indexables));

        CompletableFuture<Void> traversalFuture;
        if (traversalFutures.isEmpty()) {
            traversalFuture = CompletableFuture.completedFuture(null);
        } else {
            CompletableFuture<?>[] arr = new CompletableFuture<?>[traversalFutures.size()];
            for (int i = 0; i < traversalFutures.size(); i++) {
                arr[i] = asCompletableVoid(traversalFutures.get(i));
            }
            traversalFuture = CompletableFuture.allOf(arr);
        }

        return CompletableFuture.allOf(leafFuture, traversalFuture);
    }

    private Future<?> updateFilesAsyncForDefinition(IndexDefinition<?, ?> def, List<FileObject> files) {
        if (disposed.get() || !project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        if (def == null || files == null || files.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Filter down to supported files only (do NOT apply skip-unchanged; this is for backfill).
        ArrayList<FileObject> indexables = new ArrayList<>(files.size());
        for (FileObject f : files) {
            if (f == null || f.isFolder()) continue;
            if (!JrtExportFilter.isIndexable(f, skipNonExportedJrt)) continue;
            try {
                if (!def.supports(f)) continue;
            } catch (Throwable ignored) {
                continue;
            }
            indexables.add(f);
        }
        if (indexables.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final CompletableFuture<Void> future = new CompletableFuture<>();
        queuedFiles.addAndGet(indexables.size());
        fireProgressChanged();

        try {
            writeExecutor.submit(() -> runBatchIndexTaskForDefinition(def, indexables, future));
        } catch (RejectedExecutionException e) {
            queuedFiles.updateAndGet(v -> Math.max(0, v - indexables.size()));
            fireProgressChanged();
            future.complete(null);
        }

        return future;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void runBatchIndexTaskForDefinition(IndexDefinition def, List<FileObject> files, CompletableFuture<Void> future) {
        if (disposed.get() || !project.isOpen()) {
            future.complete(null);
            fireProgressChanged();
            return;
        }

        queuedFiles.updateAndGet(v -> Math.max(0, v - files.size()));
        runningFiles.addAndGet(files.size());
        fireProgressChanged();

        int processed = 0;
        try {
            indexLock.writeLock().lock();
            try {
                for (FileObject file : files) {
                    if (disposed.get() || !project.isOpen()) {
                        break;
                    }
                    if (file == null) {
                        continue;
                    }

                    String path = file.getPath();
                    currentFilePath.set(path);

                    int fileId = safeVfsId(file);
                    if (fileId <= 0) {
                        continue;
                    }
                    Object helper = buildHelper(file);

                    try {
                        if (def.supports(file)) {
                            updateIndex(def, file, fileId, helper);
//                            stampStore.update(def.id(), def.getVersion(), path, file);
                        }
                    } catch (Throwable ignored) {
                    }

                    processed++;
                    completedFileRuns.incrementAndGet();
                    runningFiles.updateAndGet(v -> Math.max(0, v - 1));
                    if ((processed & 0x7F) == 0) {
                        fireProgressChanged();
                    }
                }

                db.commit();
            } finally {
                indexLock.writeLock().unlock();
            }
        } catch (Throwable ignored) {
        } finally {
            int remaining = Math.max(0, files.size() - processed);
            if (remaining > 0) {
                runningFiles.updateAndGet(v -> Math.max(0, v - remaining));
            }
            currentFilePath.set(null);
            fireProgressChanged();
            future.complete(null);
        }
    }

    private Future<?> updateFilesAsyncLeaf(List<FileObject> indexables) {
        if (disposed.get() || !project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }

        if (indexables == null || indexables.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final CompletableFuture<Void> future = new CompletableFuture<>();
        queuedFiles.addAndGet(indexables.size());
        fireProgressChanged();

        try {
            writeExecutor.submit(() -> runBatchIndexTask(indexables, future));
        } catch (RejectedExecutionException e) {
            queuedFiles.updateAndGet(v -> Math.max(0, v - indexables.size()));
            fireProgressChanged();
            future.complete(null);
        }

        return future;
    }

    private void runBatchIndexTask(List<FileObject> files, CompletableFuture<Void> future) {
        if (disposed.get() || !project.isOpen()) {
            future.complete(null);
            fireProgressChanged();
            return;
        }

        // treat each file as a unit of progress, but do the work in one writer task.
        queuedFiles.updateAndGet(v -> Math.max(0, v - files.size()));
        runningFiles.addAndGet(files.size());
        fireProgressChanged();

        DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<DumbService.DumbModeToken> dumbTokenRef = new AtomicReference<>();
        ScheduledFuture<?> dumbFuture = null;

        if (dumbThresholdMs <= 0) {
            dumbTokenRef.set(dumbService.startDumbTask("Indexing: batch(" + files.size() + ")"));
        } else {
            dumbFuture = dumbScheduler.schedule(() -> {
                if (finished.get()) {
                    return;
                }

                DumbService.DumbModeToken token = dumbService.startDumbTask("Indexing: batch(" + files.size() + ")");

                if (finished.get()) {
                    token.close();
                    return;
                }

                if (!dumbTokenRef.compareAndSet(null, token)) {
                    token.close();
                }
            }, dumbThresholdMs, TimeUnit.MILLISECONDS);
        }

        int processed = 0;

        // snapshot definitions once for consistency within this batch.
        List<IndexDefinition<?, ?>> defsSnapshot = new ArrayList<>(definitions.values());

        // precompute outside the MapDB write lock to avoid blocking readers on parsing/IO.
        List<PrecomputedFile> precomputed = null;
        try {
            precomputed = maybePrecompute(files, defsSnapshot);
        } catch (Throwable ignored) {
        }

        try {
            indexLock.writeLock().lock();
            try {
                for (int i = 0; i < files.size(); i++) {
                    FileObject file = files.get(i);
                    if (disposed.get() || !project.isOpen()) {
                        break;
                    }

                    String path = file.getPath();
                    currentFilePath.set(path);

                    int fileId = safeVfsId(file);
                    if (fileId <= 0) {
                        continue;
                    }

                    PrecomputedFile pc = (precomputed != null && i < precomputed.size()) ? precomputed.get(i) : null;
                    Object helper = (pc != null) ? pc.helper : buildHelper(file);

                    for (IndexDefinition<?, ?> def : defsSnapshot) {
                        if (!def.supports(file)) {
                            continue;
                        }

//                        if (skipUnchangedFiles && !def.isOutdated(file, stampStore)) {
//                            continue;
//                        }

                        if (pc != null) {
                            applyPrecomputed(def, fileId, pc.entriesByIndexId.get(def.id()));
                        } else {
                            updateIndex(def, file, fileId, helper);
                        }

                    }
                    processed++;
                    completedFileRuns.incrementAndGet();
                    runningFiles.updateAndGet(v -> Math.max(0, v - 1));

                    // avoid spamming listeners on very large batches.
                    if ((processed & 0x7F) == 0) {
                        fireProgressChanged();
                    }
                }

                db.commit();

            } finally {
                indexLock.writeLock().unlock();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            finished.set(true);
            if (dumbFuture != null) {
                dumbFuture.cancel(false);
            }
            DumbService.DumbModeToken tok = dumbTokenRef.getAndSet(null);
            if (tok != null) {
                tok.close();
            }

            // if we bailed early, ensure counters are consistent.
            int remaining = Math.max(0, files.size() - processed);
            if (remaining > 0) {
                runningFiles.updateAndGet(v -> Math.max(0, v - remaining));
                // processed were already counted as completed; remaining were neither completed nor queued.
            }

            currentFilePath.set(null);
            fireProgressChanged();
            future.complete(null);
        }
    }

    private void submitIndexTask(PendingIndexRequest req) {
        try {
            writeExecutor.submit(() -> runIndexTask(req));
        } catch (RejectedExecutionException e) {
            // shutdown/dispose.
            PendingIndexRequest removed = pendingByPath.remove(req.path);
            if (removed != null) {
                queuedFiles.updateAndGet(v -> Math.max(0, v - 1));
                fireProgressChanged();
                removed.future.complete(null);
            }
        }
    }

    private void runIndexTask(PendingIndexRequest req) {
        if (disposed.get() || !project.isOpen()) {
            pendingByPath.remove(req.path, req);
            req.future.complete(null);
            fireProgressChanged();
            return;
        }

        String path = req.path;

        queuedFiles.updateAndGet(v -> Math.max(0, v - 1));
        runningFiles.incrementAndGet();
        currentFilePath.set(path);
        fireProgressChanged();

        long versionAtStart = req.version.get();
        FileObject file = req.fileRef.get();

        if (skipUnchangedFiles && !isOutdatedForAnyIndex(file)) {
            pendingByPath.remove(path, req);
            runningFiles.updateAndGet(v -> Math.max(0, v - 1));
            currentFilePath.compareAndSet(path, null);
            completedFileRuns.incrementAndGet();
            fireProgressChanged();
            req.future.complete(null);
            return;
        }

        DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<DumbService.DumbModeToken> dumbTokenRef = new AtomicReference<>();
        ScheduledFuture<?> dumbFuture = null;
        if (dumbThresholdMs <= 0) {
            dumbTokenRef.set(dumbService.startDumbTask("Indexing: " + path));
        } else {
            dumbFuture = dumbScheduler.schedule(() -> {
                if (finished.get()) {
                    return;
                }

                DumbService.DumbModeToken token = dumbService.startDumbTask("Indexing: " + path);

                if (finished.get()) {
                    token.close();
                    return;
                }

                if (!dumbTokenRef.compareAndSet(null, token)) {
                    token.close();
                }
            }, dumbThresholdMs, TimeUnit.MILLISECONDS);
        }

        try {
            indexLock.writeLock().lock();
            try {
                int fileId = safeVfsId(file);
                if (fileId <= 0) {
                    return;
                }

                Object helper = buildHelper(file);

                for (IndexDefinition<?, ?> def : definitions.values()) {
                    if (!def.supports(file)) {
                        continue;
                    }

//                    if (skipUnchangedFiles && !def.isOutdated(file, stampStore)) {
//                        continue;
//                    }

                    updateIndex(def, file, fileId, helper);
//                    stampStore.update(def.id(), def.getVersion(), path, file);
                }

                db.commit();

            } finally {
                indexLock.writeLock().unlock();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            finished.set(true);
            if (dumbFuture != null) {
                dumbFuture.cancel(false);
            }
            DumbService.DumbModeToken tok = dumbTokenRef.getAndSet(null);
            if (tok != null) {
                tok.close();
            }

            runningFiles.updateAndGet(v -> Math.max(0, v - 1));
            currentFilePath.compareAndSet(path, null);
            completedFileRuns.incrementAndGet();
            fireProgressChanged();
        }

        if (disposed.get() || !project.isOpen()) {
            pendingByPath.remove(path, req);
            req.future.complete(null);
            return;
        }

        long versionAfter = req.version.get();
        if (versionAfter != versionAtStart) {
            // File was requested again while we were indexing; re-queue.
            req.order.set(enqueueOrderSeq.incrementAndGet());
            queuedFiles.incrementAndGet();
            fireProgressChanged();
            submitIndexTask(req);
            return;
        }

        pendingByPath.remove(path, req);
        req.future.complete(null);
    }

    /**
     * Waits until all previously queued index write tasks are completed.
     * Useful for tests.
     */
    public void flush() {
        try {
            // barrier + drain loop: tasks may enqueue follow-up work (dedup reruns).
            // loop until the queue and running state are observed empty after a barrier.
            for (int i = 0; i < 10_000; i++) {
                writeExecutor.submit(() -> {
                    // no-op barrier
                }).get();
                if (queuedFiles.get() == 0 && runningFiles.get() == 0) {
                    return;
                }
            }
            throw new RuntimeException("Failed to flush index writer: indexing did not drain");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to flush index writer", e);
        }
    }

    private void fireProgressChanged() {
        if (progressListeners.isEmpty()) {
            return;
        }

        IndexingProgressSnapshot snapshot = getProgressSnapshot();
        for (IndexingProgressListener l : progressListeners) {
            try {
                l.onProgress(snapshot);
            } catch (Throwable ignored) {
            }
        }
    }

    private <K, V> void updateIndex(IndexDefinition<K, V> def, FileObject file, int fileId, Object helper) {
        MapDBIndexWrapper wrapper = wrappers.get(def.id());

        Map<K, V> newEntries;
        try {
            newEntries = def.map(file, helper);
        } catch (Throwable t) {
            // Indexing must be resilient to partially-typed / invalid sources.
            // Treat failures as "no entries" instead of poisoning the MapDB transaction.
            t.printStackTrace();
            newEntries = Collections.emptyMap();
        }
        if (newEntries == null) newEntries = Collections.emptyMap();

        Set<String> oldKeys = wrapper.getForwardKeys(fileId);

        // For every old key, we must check if we need to remove the entry.
        // Optimization: We serialize the OLD value (from disk) is hard without storing it.
        // Instead, we just assume that if the key is no longer in 'newEntries',
        // OR if the value changed, we remove the OLD entry associated with this file.
        //
        // Since we don't store the old value in ForwardIndex (only keys),
        // we can iterate the Inverted Index for that key, deserialize, check isValueForFile, and remove.

        for (String oldKey : oldKeys) {
            wrapper.removeInvertedByFileId(oldKey, fileId);
        }

        Set<String> newKeysForForward = new HashSet<>();

        for (Map.Entry<K, V> entry : newEntries.entrySet()) {
            String keyStr = entry.getKey().toString();
            byte[] valBytes = def.serializeValue(entry.getValue());

            wrapper.putInverted(keyStr, fileId, valBytes);
            newKeysForForward.add(keyStr);
        }

        wrapper.putForward(fileId, newKeysForForward);
    }

    /**
     * Builds a per-file helper object that can be reused by multiple index definitions.
     * <p>
     * Today this is optimized for binary class indexing: for `.class` files we pre-read bytes once
     * and pass them to all indexers so they can avoid repeated I/O and share parsing.
     */
    private Object buildHelper(FileObject file) {
        try {
            if (file != null && "class".equalsIgnoreCase(file.getExtension())) {
                return file.getContent();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private record PrecomputedEntry(String key, byte[] valueBytes) {
    }

    private record PrecomputedFile(Object helper, Map<String, List<PrecomputedEntry>> entriesByIndexId) {
    }

    private List<PrecomputedFile> maybePrecompute(List<FileObject> files, List<IndexDefinition<?, ?>> defsSnapshot) {
        if (precomputeExecutor == null) {
            return null;
        }
        if (files == null || files.isEmpty()) {
            return null;
        }
        if (defsSnapshot == null || defsSnapshot.isEmpty()) {
            return null;
        }

        // avoid overhead for tiny batches.
        if (files.size() < 256) {
            return null;
        }

        ArrayList<CompletableFuture<PrecomputedFile>> futures = new ArrayList<>(files.size());
        for (FileObject file : files) {
            futures.add(CompletableFuture.supplyAsync(() -> precomputeOne(file, defsSnapshot), precomputeExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        ArrayList<PrecomputedFile> result = new ArrayList<>(files.size());
        for (CompletableFuture<PrecomputedFile> f : futures) {
            try {
                result.add(f.getNow(null));
            } catch (Throwable ignored) {
                result.add(null);
            }
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PrecomputedFile precomputeOne(FileObject file, List<IndexDefinition<?, ?>> defsSnapshot) {
        if (disposed.get() || !project.isOpen()) {
            return null;
        }

        Object helper = buildHelper(file);
        Map<String, List<PrecomputedEntry>> byIndexId = new HashMap<>();

        for (IndexDefinition def : defsSnapshot) {
            if (def == null) continue;
            try {
                if (!def.supports(file)) {
                    continue;
                }

//                if (skipUnchangedFiles && !def.isOutdated(file, stampStore)) {
//                    continue;
//                }

                Map<?, ?> mapped;
                try {
                    mapped = def.map(file, helper);
                } catch (Throwable t) {
                    t.printStackTrace();
                    mapped = Collections.emptyMap();
                }
                if (mapped == null) {
                    mapped = Collections.emptyMap();
                }

                if (mapped.isEmpty()) {
                    byIndexId.put(def.id(), List.of());
                    continue;
                }

                ArrayList<PrecomputedEntry> entries = new ArrayList<>(mapped.size());
                for (Map.Entry<?, ?> e : ((Map<?, ?>) mapped).entrySet()) {
                    if (e == null) continue;
                    Object k = e.getKey();
                    Object v = e.getValue();
                    if (k == null || v == null) continue;

                    String keyStr = k.toString();
                    byte[] valBytes = def.serializeValue(v);
                    entries.add(new PrecomputedEntry(keyStr, valBytes));
                }
                byIndexId.put(def.id(), entries);
            } catch (Throwable ignored) {
                // best-effort
            }
        }

        return new PrecomputedFile(helper, byIndexId);
    }

    private void applyPrecomputed(IndexDefinition<?, ?> def, int fileId, List<PrecomputedEntry> entries) {
        MapDBIndexWrapper wrapper = wrappers.get(def.id());
        if (wrapper == null) {
            return;
        }

        Set<String> oldKeys = wrapper.getForwardKeys(fileId);
        for (String oldKey : oldKeys) {
            wrapper.removeInvertedByFileId(oldKey, fileId);
        }

        if (entries == null || entries.isEmpty()) {
            wrapper.putForward(fileId, Collections.emptySet());
            return;
        }

        Set<String> newKeysForForward = new HashSet<>(Math.max(16, entries.size()));
        for (PrecomputedEntry e : entries) {
            if (e == null || e.key == null || e.valueBytes == null) continue;

            byte[] valBytes = e.valueBytes;
            wrapper.putInverted(e.key, fileId, valBytes);
            newKeysForForward.add(e.key);
        }

        wrapper.putForward(fileId, newKeysForForward);
    }

    private Future<?> maybeIndexRecursively(FileObject file) {
        try {
            if (file == null) {
                return null;
            }

            // folder roots: traverse children (includes jrt:/ and jar: roots).
            if (file.isFolder()) {
                return indexRecursivelyAsync(file);
            }

            // ocal .jar file: resolve jar root (jar:...!/) and traverse.
            if (isLocalJarFile(file)) {
                FileObject jarRoot = resolveJarRoot(file);
                if (jarRoot != null && jarRoot.isFolder()) {
                    return indexRecursivelyAsync(jarRoot);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isLocalJarFile(FileObject file) {
        if (file == null) return false;
        if (file.isFolder()) return false;
        return "jar".equalsIgnoreCase(file.getExtension());
    }

    private FileObject resolveJarRoot(FileObject jarFile) {
        try {
            URI jarRoot = URI.create("jar:" + jarFile.toUri() + "!/");
            return VirtualFileManager.getInstance().find(jarRoot);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Future<?> indexRecursivelyAsync(FileObject root) {
        if (disposed.get() || !project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            scanExecutor.submit(() -> {
                try {
                    indexRecursively(root).whenComplete((v, t) -> {
                        if (t != null) {
                            result.completeExceptionally(t);
                        } else {
                            result.complete(null);
                        }
                    });
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            result.complete(null);
        }

        return result;
    }

    private CompletableFuture<Void> indexRecursively(FileObject root) {
        if (root == null) {
            return CompletableFuture.completedFuture(null);
        }

        Deque<FileObject> stack = new ArrayDeque<>();
        stack.push(root);

        ArrayList<FileObject> batch = new ArrayList<>(traversalBatchSize);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        while (!stack.isEmpty()) {
            if (disposed.get() || !project.isOpen()) {
                break;
            }

            FileObject cur = stack.pop();
            if (cur == null) continue;

            if (cur.isFolder()) {
                List<FileObject> kids;
                try {
                    kids = cur.getChildren();
                } catch (Throwable ignored) {
                    kids = List.of();
                }
                if (kids == null || kids.isEmpty()) continue;
                for (FileObject k : kids) {
                    if (k != null) {
                        stack.push(k);
                    }
                }
                continue;
            }

            if (!shouldIndex(cur)) {
                continue;
            }

            batch.add(cur);
            if (batch.size() >= traversalBatchSize) {
                List<FileObject> toSubmit = new ArrayList<>(batch);
                batch.clear();
                chain = chain.thenCompose(v -> asCompletableVoid(updateFilesAsyncLeaf(toSubmit)));
            }
        }

        if (!batch.isEmpty()) {
            List<FileObject> toSubmit = new ArrayList<>(batch);
            batch.clear();
            chain = chain.thenCompose(v -> asCompletableVoid(updateFilesAsyncLeaf(toSubmit)));
        }

        return chain;
    }

    private static CompletableFuture<Void> asCompletableVoid(Future<?> future) {
        if (future == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (future instanceof CompletableFuture<?> cf) {
            return cf.thenApply(v -> null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                future.get();
            } catch (Throwable ignored) {
            }
        }, ForkJoinPool.commonPool());
    }

    /**
     * Iterates over values for a specific exact key.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processValues(String indexId, String key, com.tyron.nanoj.api.indexing.SearchScope scope, com.tyron.nanoj.api.indexing.IndexProcessor<V> processor) {
        throw new UnsupportedOperationException();
    }

    /**
     * Iterates over values where the key starts with the given prefix.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processPrefix(String indexId, String prefix, com.tyron.nanoj.api.indexing.SearchScope scope, com.tyron.nanoj.api.indexing.IndexProcessor<V> processor) {
     throw new UnsupportedOperationException();
    }

    /**
     * Iterates over values where the key starts with the given prefix, also exposing the matched key.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processPrefixWithKeys(String indexId, String prefix, com.tyron.nanoj.api.indexing.SearchScope scope, com.tyron.nanoj.api.indexing.KeyedIndexProcessor<V> processor) {
       throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    public <K, V> List<V> search(String indexId, String key) {
        List<V> values = new ArrayList<>();
        processValues(indexId, key, com.tyron.nanoj.api.indexing.SearchScope.all(), (fileId, value) -> {
            values.add((V) value);
            return true;
        });
        return values;
    }

    /**
     * Efficient Prefix Search via B-Tree.
     */
    @SuppressWarnings("unchecked")
    public <K, V> Map<String, List<V>> searchPrefix(String indexId, String prefix) {
       throw new UnsupportedOperationException();
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }

        if (vfsListenerAttached.get()) {
            VirtualFileManager.getInstance().removeGlobalListener(vfsListener);
        }

        // Stop scheduling any "enter dumb mode" timers.
        dumbScheduler.shutdownNow();

        try {
            scanExecutor.shutdownNow();
        } catch (Throwable ignored) {
        }

        try {
            if (precomputeExecutor != null) {
                precomputeExecutor.shutdownNow();
            }
        } catch (Throwable ignored) {
        }

        // Drain queued writes before closing MapDB (prevents NPEs from background tasks).
        boolean isWriterThread = Thread.currentThread().getName().contains("Index-Writer");
        if (!isWriterThread) {
            try {
                flush();
            } catch (Throwable ignored) {
                // best-effort drain
            }
        }

        writeExecutor.shutdown();

        indexLock.writeLock().lock();
        try {
            for (SharedIndexStore store : sharedStores) {
                try {
                    if (store.db != null && !store.db.isClosed()) {
                        store.db.close();
                    }
                } catch (Throwable ignored) {
                }
            }

            if (!db.isClosed()) {
                db.commit();
                db.close();
            }
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    private static long readLongConfig(Project project, String key, long defaultValue) {
        try {
            String value = project.getConfiguration().getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Long.parseLong(value.trim());
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private static boolean readBooleanConfig(Project project, String key, boolean defaultValue) {
        try {
            String value = project.getConfiguration().getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            String v = value.trim().toLowerCase(Locale.ROOT);
            return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v) || "on".equals(v);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /**
     * Extracts the 4-byte int File ID from the beginning of the packet.
     * Protocol: [ID_B3, ID_B2, ID_B1, ID_B0, DATA...] (Big Endian)
     */
    private int readFileId(byte[] packet) {
        return ((packet[0] & 0xFF) << 24) |
                ((packet[1] & 0xFF) << 16) |
                ((packet[2] & 0xFF) << 8)  |
                ((packet[3] & 0xFF));
    }

    /**
     * Extracts the actual value data from the packet (strips the first 4 bytes).
     */
    private byte[] unwrapPayload(byte[] packet) {
        // Optimization: In a highly optimized environment, IndexDefinition
        // would take (byte[], offset, len) to avoid this copy.
        // For now, Arrays.copyOfRange is safe and clean.
        return Arrays.copyOfRange(packet, 4, packet.length);
    }

    private static int safeVfsId(FileObject file) {
        if (file == null) {
            return -1;
        }
        try {
            return file.getId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private Future<?> removeFileAsync(FileObject file) {
        if (disposed.get() || !project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        if (file == null) {
            return CompletableFuture.completedFuture(null);
        }

        final int fileId = safeVfsId(file);
        if (fileId <= 0) {
            return CompletableFuture.completedFuture(null);
        }

        final CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            writeExecutor.submit(() -> {
                try {
                    removeFileFromAllIndices(fileId);
                } catch (Throwable ignored) {
                } finally {
                    future.complete(null);
                }
            });
        } catch (RejectedExecutionException e) {
            future.complete(null);
        }
        return future;
    }

    private void removeFileFromAllIndices(int fileId) {
        if (fileId <= 0) {
            return;
        }

        indexLock.writeLock().lock();
        try {
            for (MapDBIndexWrapper wrapper : wrappers.values()) {
                if (wrapper == null) continue;
                Set<String> oldKeys;
                try {
                    oldKeys = wrapper.getForwardKeys(fileId);
                } catch (Throwable t) {
                    continue;
                }

                for (String oldKey : oldKeys) {
                    try {
                        wrapper.removeInvertedByFileId(oldKey, fileId);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    wrapper.removeForward(fileId);
                } catch (Throwable ignored) {
                }
            }
            try {
                db.commit();
            } catch (Throwable ignored) {
            }
        } finally {
            indexLock.writeLock().unlock();
        }
    }
}