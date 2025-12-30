package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.indexing.IndexingProgressListener;
import com.tyron.nanoj.api.indexing.IndexingProgressSnapshot;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.vfs.*;
import com.tyron.nanoj.core.dumb.DumbCore;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
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

    public static IndexManager getInstance(Project project) {
        return ProjectServiceManager.getService(project, IndexManager.class);
    }

    private final DB db;
    private final List<SharedIndexStore> sharedStores = new CopyOnWriteArrayList<>();
    private final ExecutorService writeExecutor;

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
    private final AtomicReference<String> currentFilePath = new AtomicReference<>(null);

    private final FileChangeListener vfsListener;
    private final AtomicBoolean vfsListenerAttached = new AtomicBoolean(false);

    private final AtomicBoolean disposed = new AtomicBoolean(false);


    private final Project project;

    private final long dumbThresholdMs;

    // index Registries
    private final Map<String, IndexDefinition<?, ?>> definitions = new ConcurrentHashMap<>();
    private final Map<String, MapDBIndexWrapper> wrappers = new ConcurrentHashMap<>();

    // thread-safe access: readers (completion) acquire read lock; writers (indexing) acquire write lock
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
        final AtomicBoolean fromVfsEvent = new AtomicBoolean(false);

        PendingIndexRequest(String path, FileObject initialFile, long order, boolean fromVfsEvent) {
            this.path = path;
            this.fileRef = new AtomicReference<>(initialFile);
            this.future = new CompletableFuture<>();
            this.order.set(order);
            this.version.set(1L);
            this.fromVfsEvent.set(fromVfsEvent);
        }

        void update(FileObject file, long order, boolean fromVfsEvent) {
            this.fileRef.set(file);
            this.order.set(order);
            this.version.incrementAndGet();
            if (fromVfsEvent) {
                this.fromVfsEvent.set(true);
            }
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


        this.vfsListener = new FileChangeListener() {
            @Override
            public void fileCreated(FileEvent event) {
                FileObject file = event != null ? event.getFile() : null;
                if (shouldIndexFromVfsEvent(file)) {
                    updateFileFromVfsEvent(file);
                }
            }

            @Override
            public void fileDeleted(FileEvent event) {
                // VFS knows about deletes (including those detected by refresh diff). Remove index data.
                FileObject file = event != null ? event.getFile() : null;
                if (shouldIndexFromVfsEvent(file)) {
                    removeFileAsync(file);
                }
            }

            @Override
            public void fileChanged(FileEvent event) {
                FileObject file = event != null ? event.getFile() : null;
                if (shouldIndexFromVfsEvent(file)) {
                    updateFileFromVfsEvent(file);
                }
            }

            @Override
            public void fileRenamed(FileEvent event) {
                FileObject newFile = event.getFile();
                FileObject oldFile = (event instanceof FileRenameEvent re) ? re.getOldFile() : null;

                boolean newAllowed = shouldIndexFromVfsEvent(newFile);
                boolean oldAllowed = shouldIndexFromVfsEvent(oldFile);

                int newId = safeVfsId(newFile);
                int oldId = safeVfsId(oldFile);

                // If the VFS provides stable ids across rename, updating the new file is sufficient.
                // If ids differ (best-effort), also remove stale data for the old id.
                if (oldAllowed) {
                    if (!newAllowed) {
                        removeFileAsync(oldFile);
                    } else if (oldId > 0 && newId > 0 && oldId != newId) {
                        removeFileAsync(oldFile);
                    }
                }

                if (newAllowed) {
                    updateFileFromVfsEvent(newFile);
                }
            }
        };
    }

    private void updateFileFromVfsEvent(FileObject file) {

    }

    private boolean shouldIndexFromVfsEvent(FileObject file) {
        if (file == null || disposed.get() || !project.isOpen()) {
            return false;
        }

        // Avoid expensive container/recursive indexing from VFS events.
        try {
            if (file.isFolder()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        String path = safePath(file);
        if (path == null || path.isBlank()) {
            return false;
        }

        String buildDirPath = safePath(safeBuildDir());
        if (isInOrUnder(path, buildDirPath)) {
            return false;
        }

        List<FileObject> roots;
        try {
            roots = project.getSourceRoots();
        } catch (Throwable ignored) {
            roots = List.of();
        }
        if (roots == null || roots.isEmpty()) {
            return false;
        }

        for (FileObject r : roots) {
            String rp = safePath(r);
            if (isInOrUnder(path, rp)) {
                return true;
            }
        }

        return false;
    }

    private FileObject safeBuildDir() {
        try {
            return project.getBuildDirectory();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safePath(FileObject file) {
        if (file == null) return null;
        try {
            return file.getPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isInOrUnder(String path, String dirPath) {
        if (path == null || dirPath == null || dirPath.isBlank()) return false;
        if (path.equals(dirPath)) return true;
        if (!path.startsWith(dirPath)) return false;
        int n = dirPath.length();
        return path.length() > n && path.charAt(n) == '/';
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

        definitions.put(def.getId(), def);
        MapDBIndexWrapper wrapper = wrappers.computeIfAbsent(def.getId(), id -> new MapDBIndexWrapper(db, id));

        try {
            Integer prev = definitionVersionStore.getStoredVersion(def.getId());
            if (prev == null || prev != def.getVersion()) {
                definitionVersionStore.putVersion(def.getId(), def.getVersion());

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
                MapDBIndexWrapper w = MapDBIndexWrapper.openExisting(store.db, def.getId());
                if (w != null) {
                    store.wrappers.put(def.getId(), w);
                } else {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("Shared index missing maps for indexId='" + def.getId() + "' in " + store.file);
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
                    MapDBIndexWrapper w = MapDBIndexWrapper.openExisting(sharedDb, def.getId());
                    if (w != null) {
                        store.wrappers.put(def.getId(), w);
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
        try {
            return stampStore.getAllStampedPathsSnapshot();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public void updateFile(FileObject file) {

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
                    if (def.isOutdated(file, stampStore)) {
                        return true;
                    }
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
    public Future<?> updateFilesAsync(Iterable<FileObject> files) {

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
        System.out.println("Updating index: " + file.getName());
        MapDBIndexWrapper wrapper = wrappers.get(def.getId());

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

            byte[] packet = new byte[4 + valBytes.length];
            packet[0] = (byte) (fileId >> 24);
            packet[1] = (byte) (fileId >> 16);
            packet[2] = (byte) (fileId >> 8);
            packet[3] = (byte) (fileId);

            System.arraycopy(valBytes, 0, packet, 4, valBytes.length);

            wrapper.putInverted(keyStr, packet);
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

    /**
     * Iterates over values for a specific exact key.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processValues(String indexId, String key, SearchScope scope, IndexProcessor<V> processor) {
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return true;

            List<byte[]> rawPackets = wrapper.getValues(key); // wrapper gets from MapDB

            for (byte[] packet : rawPackets) {
                if (packet.length < 4) continue;

                int fileId = readFileId(packet);

                if (scope.contains(fileId)) {
                    byte[] payload = unwrapPayload(packet);
                    V value = def.deserializeValue(payload);

                    if (!processor.process(fileId, value)) {
                        return false;
                    }
                }
            }

            if (hasSharedStores()) {
                for (int i = 0; i < sharedStores.size(); i++) {
                    SharedIndexStore store = sharedStores.get(i);
                    MapDBIndexWrapper sharedWrapper = store.wrappers.get(indexId);
                    if (sharedWrapper == null) {
                        continue;
                    }

                    List<byte[]> sharedPackets = sharedWrapper.getValues(key);
                    for (byte[] packet : sharedPackets) {
                        if (packet.length < 4) continue;

                        int sharedFileId = readFileId(packet);
                        int globalFileId = encodeSharedFileId(i, sharedFileId);

                        if (scope.contains(globalFileId)) {
                            byte[] payload = unwrapPayload(packet);
                            V value = def.deserializeValue(payload);

                            if (!processor.process(globalFileId, value)) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Iterates over values where the key starts with the given prefix.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processPrefix(String indexId, String prefix, SearchScope scope, IndexProcessor<V> processor) {
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return true;

            // wrapper.searchPrefix returns Map<String, List<byte[]>>
            Map<String, List<byte[]>> prefixMatches = wrapper.searchPrefix(prefix);

            for (Map.Entry<String, List<byte[]>> entry : prefixMatches.entrySet()) {
                List<byte[]> packets = entry.getValue();

                for (byte[] packet : packets) {
                    if (packet.length < 4) continue;

                    int fileId = readFileId(packet);

                    if (scope.contains(fileId)) {
                        byte[] payload = unwrapPayload(packet);
                        V value = def.deserializeValue(payload);

                        if (!processor.process(fileId, value)) {
                            return false;
                        }
                    }
                }
            }

            if (hasSharedStores()) {
                for (int i = 0; i < sharedStores.size(); i++) {
                    SharedIndexStore store = sharedStores.get(i);
                    MapDBIndexWrapper sharedWrapper = store.wrappers.get(indexId);
                    if (sharedWrapper == null) {
                        continue;
                    }

                    Map<String, List<byte[]>> sharedPrefixMatches = sharedWrapper.searchPrefix(prefix);
                    for (Map.Entry<String, List<byte[]>> entry : sharedPrefixMatches.entrySet()) {
                        List<byte[]> packets = entry.getValue();
                        for (byte[] packet : packets) {
                            if (packet.length < 4) continue;
                            int sharedFileId = readFileId(packet);
                            int globalFileId = encodeSharedFileId(i, sharedFileId);

                            if (scope.contains(globalFileId)) {
                                byte[] payload = unwrapPayload(packet);
                                V value = def.deserializeValue(payload);

                                if (!processor.process(globalFileId, value)) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
            return true;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Iterates over values where the key starts with the given prefix, also exposing the matched key.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processPrefixWithKeys(String indexId, String prefix, SearchScope scope, KeyedIndexProcessor<V> processor) {
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return true;

            Map<String, List<byte[]>> prefixMatches = wrapper.searchPrefix(prefix);

            for (Map.Entry<String, List<byte[]>> entry : prefixMatches.entrySet()) {
                String key = entry.getKey();
                List<byte[]> packets = entry.getValue();

                for (byte[] packet : packets) {
                    if (packet.length < 4) continue;

                    int fileId = readFileId(packet);

                    if (scope.contains(fileId)) {
                        byte[] payload = unwrapPayload(packet);
                        V value = def.deserializeValue(payload);

                        if (!processor.process(key, fileId, value)) {
                            return false;
                        }
                    }
                }
            }

            if (hasSharedStores()) {
                for (int i = 0; i < sharedStores.size(); i++) {
                    SharedIndexStore store = sharedStores.get(i);
                    MapDBIndexWrapper sharedWrapper = store.wrappers.get(indexId);
                    if (sharedWrapper == null) {
                        continue;
                    }

                    Map<String, List<byte[]>> sharedPrefixMatches = sharedWrapper.searchPrefix(prefix);
                    for (Map.Entry<String, List<byte[]>> entry : sharedPrefixMatches.entrySet()) {
                        String key = entry.getKey();
                        List<byte[]> packets = entry.getValue();

                        for (byte[] packet : packets) {
                            if (packet.length < 4) continue;

                            int sharedFileId = readFileId(packet);
                            int globalFileId = encodeSharedFileId(i, sharedFileId);

                            if (scope.contains(globalFileId)) {
                                byte[] payload = unwrapPayload(packet);
                                V value = def.deserializeValue(payload);

                                if (!processor.process(key, globalFileId, value)) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }

            return true;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public <K, V> List<V> search(String indexId, String key) {
        List<V> values = new ArrayList<>();
        processValues(indexId, key, SearchScope.all(), (fileId, value) -> {
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
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return Collections.emptyMap();

            Map<String, List<byte[]>> rawMap = wrapper.searchPrefix(prefix);
            Map<String, List<V>> result = new HashMap<>();

            for (Map.Entry<String, List<byte[]>> entry : rawMap.entrySet()) {
                List<V> decoded = new ArrayList<>();
                for (byte[] b : entry.getValue()) {
                    decoded.add(def.deserializeValue(b));
                }
                result.put(entry.getKey(), decoded);
            }
            return result;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }

        if (vfsListenerAttached.get()) {
            VirtualFileManager.getInstance().removeGlobalListener(vfsListener);
        }

        // drain queued writes before closing MapDB (prevents NPEs from background tasks).
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