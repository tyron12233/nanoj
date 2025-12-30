package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileEvent;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileSystem;
import com.tyron.nanoj.api.vfs.FileRenameEvent;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.core.vfs.persistent.PersistentVfs;
import com.tyron.nanoj.core.vfs.persistent.PersistentVfsRecord;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of {@link VirtualFileManager}.
 */
public class VirtualFileManagerImpl implements VirtualFileManager, Disposable {

    // Map Scheme -> FileSystem ("file" -> LocalFileSystem)
    private final Map<String, FileSystem> registry = new ConcurrentHashMap<>();

    // FileSystem -> Bridge listener (for cleanup when FS is replaced/unregistered)
    private final Map<FileSystem, FileChangeListener> bridges = new ConcurrentHashMap<>();

    // Global listeners
    private final CopyOnWriteArrayList<FileChangeListener> globalListeners = new CopyOnWriteArrayList<>();

    private final PersistentVfs persistentVfs;

    public VirtualFileManagerImpl() {
        this.persistentVfs = new PersistentVfs(defaultVfsDbPath());

        // Register default Local NIO FS
        register(LocalFileSystem.getInstance());

        // Register read-only jar filesystem
        register(JarFileSystem.getInstance());

        // Register read-only jrt filesystem (JDK modules)
        register(JrtFileSystem.getInstance());
    }

    @Override
    public void dispose() {
        // Detach bridges first: otherwise singleton file systems (e.g. LocalFileSystem)
        // can keep calling this instance after its DB is closed.
        try {
            for (FileSystem fs : registry.values()) {
                detachBridge(fs);
            }
            bridges.clear();
            registry.clear();
            globalListeners.clear();
        } catch (Throwable ignored) {
            // Best-effort.
        } finally {
            try {
                persistentVfs.close();
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }
    }

    @Override
    public void register(FileSystem fs) {
        Objects.requireNonNull(fs, "fs");

        String scheme = normalizeScheme(fs.getScheme());
        FileSystem previous = registry.put(scheme, fs);
        if (previous != null) {
            detachBridge(previous);
        }

        attachBridge(fs);
    }

    @Override
    public void unregister(String scheme) {
        String key = normalizeScheme(scheme);
        FileSystem removed = registry.remove(key);
        if (removed != null) {
            detachBridge(removed);
        }
    }

    @Override
    public FileSystem getFileSystem(String scheme) {
        return registry.get(normalizeScheme(scheme));
    }

    @Override
    public Set<String> getRegisteredSchemes() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    @Override
    public void refreshAll(boolean asynchronous) {
        for (FileSystem fs : registry.values()) {
            try {
                fs.refresh(asynchronous);
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }

        if (asynchronous) {
            CompletableFuture.runAsync(this::syncTrackedRoots);
        } else {
            syncTrackedRoots();
        }
    }

    @Override
    public FileObject find(File file) {
        return find(file.toURI());
    }

    @Override
    public FileObject find(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme();
        if (scheme == null) scheme = "file";
        scheme = normalizeScheme(scheme);

        FileSystem fs = registry.get(scheme);
        if (fs == null) {
            throw new UnsupportedOperationException("No FileSystem registered for scheme: " + scheme);
        }
        return fs.findResource(uri);
    }

    @Override
    public FileObject find(String pathOrUri) {
        Objects.requireNonNull(pathOrUri, "pathOrUri");

        int schemeIdx = pathOrUri.indexOf("://");
        if (schemeIdx > 0) {
            return find(URI.create(pathOrUri));
        }
        return find(new File(pathOrUri));
    }

    @Override
    public int getFileId(FileObject file) {
        Objects.requireNonNull(file, "file");
        PersistentVfsRecord record = recordOf(file);
        return persistentVfs.getOrCreateId(file.toUri(), record.getFileKey());
    }

    @Override
    public FileObject findById(int id) {
        URI uri = persistentVfs.getUriById(id);
        if (uri == null) return null;
        return find(uri);
    }

    @Override
    public void trackRoot(FileObject root) {
        Objects.requireNonNull(root, "root");
        persistentVfs.trackRoot(root.toUri());
    }

    @Override
    public void addGlobalListener(FileChangeListener l) {
        globalListeners.add(l);
    }

    @Override
    public void removeGlobalListener(FileChangeListener l) {
        globalListeners.remove(l);
    }

    @Override
    public void fireFileCreated(FileObject fo) {
        FileEvent event = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileCreated(event);
    }

    @Override
    public void fireFileDeleted(FileObject fo) {
        FileEvent event = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileDeleted(event);
    }

    @Override
    public void fireFileChanged(FileObject fo) {
        FileEvent event = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileChanged(event);
    }

    @Override
    public void fireFileRenamed(FileObject oldFile, FileObject newFile) {
        FileRenameEvent event = new FileRenameEvent(oldFile, newFile);
        for (FileChangeListener l : globalListeners) l.fileRenamed(event);
    }

    private void fireFileRenamed(FileEvent event) {
        for (FileChangeListener l : globalListeners) l.fileRenamed(event);
    }

    @Override
    @TestOnly
    public void clear() {
        for (FileSystem fs : registry.values()) {
            detachBridge(fs);
        }
        registry.clear();
        globalListeners.clear();

        synchronized (persistentVfs) {
            persistentVfs.storage().uriToId.clear();
            persistentVfs.storage().idToUri.clear();
            persistentVfs.storage().fileKeyToId.clear();
            persistentVfs.storage().recordsByUri.clear();
            persistentVfs.storage().trackedRoots.clear();
            persistentVfs.storage().nextId.set(0);
            persistentVfs.storage().commit();
        }

        register(LocalFileSystem.getInstance());
        register(JarFileSystem.getInstance());
        register(JrtFileSystem.getInstance());
    }

    private void attachBridge(FileSystem fs) {
        FileChangeListener bridge = new FileChangeListener() {
            @Override
            public void fileCreated(FileEvent event) {
                PersistentVfsRecord record = recordOf(event.getFile());
                persistentVfs.onCreated(event.getFile(), record);
                fireFileCreated(event.getFile());
            }

            @Override
            public void fileDeleted(FileEvent event) {
                persistentVfs.onDeleted(event.getFile().toUri());
                fireFileDeleted(event.getFile());
            }

            @Override
            public void fileChanged(FileEvent event) {
                PersistentVfsRecord record = recordOf(event.getFile());
                persistentVfs.onChanged(event.getFile(), record);
                fireFileChanged(event.getFile());
            }

            @Override
            public void fileRenamed(FileEvent event) {
                if (event instanceof FileRenameEvent re) {
                    PersistentVfsRecord newRecord = recordOf(re.getFile());
                    persistentVfs.onRenamed(re.getOldFile().toUri(), re.getFile().toUri(), newRecord.getFileKey(), newRecord);
                }
                fireFileRenamed(event);
            }
        };

        bridges.put(fs, bridge);
        fs.addFileChangeListener(bridge);
    }

    private void detachBridge(FileSystem fs) {
        FileChangeListener bridge = bridges.remove(fs);
        if (bridge != null) {
            fs.removeFileChangeListener(bridge);
        }
    }

    private static String normalizeScheme(String scheme) {
        if (scheme == null || scheme.isBlank()) return "file";
        return scheme.toLowerCase();
    }

    private static Path defaultVfsDbPath() {
        String override = System.getProperty("nanoj.vfs.db");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        // Tests (often running in parallel forks) must not share a single mmap DB file.
        // Use a per-worker/per-process temp location.
        if (isTestEnvironment()) {
            String worker = System.getProperty("org.gradle.test.worker");
            long pid;
            try {
                pid = ProcessHandle.current().pid();
            } catch (Throwable ignored) {
                pid = -1L;
            }

            String suffix = (worker != null && !worker.isBlank()) ? ("worker-" + worker) : ("pid-" + pid);
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp == null || tmp.isBlank()) {
                tmp = System.getProperty("user.home", ".");
            }

            return Path.of(tmp, "nanoj", "vfs", suffix, "vfs.db").toAbsolutePath().normalize();
        }

        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getProperty("java.io.tmpdir");
        }
        return Path.of(home, ".nanoj", "vfs", "vfs.db").toAbsolutePath().normalize();
    }

    private static boolean isTestEnvironment() {
        String explicit = System.getProperty("nanoj.vfs.test");
        if (explicit != null) {
            return Boolean.parseBoolean(explicit);
        }

        // Heuristics: JUnit/Gradle test workers.
        if (System.getProperty("org.gradle.test.worker") != null) {
            return true;
        }
        String cp = System.getProperty("java.class.path", "");
        String lower = cp.toLowerCase();
        return lower.contains("junit") || lower.contains("surefire") || lower.contains("testng");
    }

    private void syncTrackedRoots() {
        List<String> roots;
        synchronized (persistentVfs) {
            roots = List.copyOf(persistentVfs.storage().trackedRoots.getKeys());
        }

        for (String rootUriStr : roots) {
            try {
                syncRoot(URI.create(rootUriStr));
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }
    }

    private void syncRoot(URI rootUri) throws IOException {
        FileObject root = find(rootUri);
        boolean rootIsFolder = root.exists() && root.isFolder();
        String rootPrefix = PersistentVfs.rootPrefixOf(root.toUri(), rootIsFolder);
        String rootPrefixEnd = rootPrefix + "\uffff";

        Map<String, PersistentVfsRecord> oldRecords;
        synchronized (persistentVfs) {
            oldRecords = new LinkedHashMap<>(persistentVfs.storage().recordsByUri.subMap(rootPrefix, true, rootPrefixEnd, true));
        }

        Map<String, PersistentVfsRecord> newRecords = scanTree(root);

        Map<String, String> renames = detectRenamesByFileKey(oldRecords, newRecords);

        Set<String> deleted = new HashSet<>();
        Set<String> created = new HashSet<>();
        Set<String> changed = new HashSet<>();

        for (String oldUri : oldRecords.keySet()) {
            if (renames.containsKey(oldUri)) continue;
            if (!newRecords.containsKey(oldUri)) {
                deleted.add(oldUri);
            }
        }

        for (String newUri : newRecords.keySet()) {
            if (renames.containsValue(newUri)) continue;
            if (!oldRecords.containsKey(newUri)) {
                created.add(newUri);
            }
        }

        for (Map.Entry<String, PersistentVfsRecord> e : newRecords.entrySet()) {
            String uri = e.getKey();
            if (!oldRecords.containsKey(uri)) continue;
            if (renames.containsKey(uri) || renames.containsValue(uri)) continue;
            PersistentVfsRecord old = oldRecords.get(uri);
            PersistentVfsRecord neu = e.getValue();
            if (!neu.equals(old)) {
                changed.add(uri);
            }
        }

        synchronized (persistentVfs) {
            for (String uriStr : deleted) {
                persistentVfs.storage().recordsByUri.remove(uriStr);
            }
            for (Map.Entry<String, String> rn : renames.entrySet()) {
                String oldUriStr = rn.getKey();
                String newUriStr = rn.getValue();
                PersistentVfsRecord r = newRecords.get(newUriStr);
                String fileKey = r != null ? r.getFileKey() : null;
                persistentVfs.onRenamed(URI.create(oldUriStr), URI.create(newUriStr), fileKey, r);
            }
            for (String uriStr : created) {
                PersistentVfsRecord r = newRecords.get(uriStr);
                persistentVfs.getOrCreateId(URI.create(uriStr), r != null ? r.getFileKey() : null);
                persistentVfs.storage().recordsByUri.put(uriStr, r);
            }
            for (String uriStr : changed) {
                PersistentVfsRecord r = newRecords.get(uriStr);
                persistentVfs.getOrCreateId(URI.create(uriStr), r != null ? r.getFileKey() : null);
                persistentVfs.storage().recordsByUri.put(uriStr, r);
            }
            persistentVfs.storage().commit();
        }

        for (Map.Entry<String, String> rn : renames.entrySet()) {
            FileObject oldFo = find(URI.create(rn.getKey()));
            FileObject newFo = find(URI.create(rn.getValue()));
            fireFileRenamed(oldFo, newFo);
        }
        for (String uriStr : created) {
            fireFileCreated(find(URI.create(uriStr)));
        }
        for (String uriStr : changed) {
            fireFileChanged(find(URI.create(uriStr)));
        }
        for (String uriStr : deleted) {
            fireFileDeleted(find(URI.create(uriStr)));
        }
    }

    private static Map<String, String> detectRenamesByFileKey(
            Map<String, PersistentVfsRecord> oldRecords,
            Map<String, PersistentVfsRecord> newRecords
    ) {
        Map<String, String> oldByKey = new HashMap<>();
        for (Map.Entry<String, PersistentVfsRecord> e : oldRecords.entrySet()) {
            String key = e.getValue().getFileKey();
            if (key != null && !key.isBlank()) {
                oldByKey.putIfAbsent(key, e.getKey());
            }
        }

        Map<String, String> renames = new HashMap<>();
        for (Map.Entry<String, PersistentVfsRecord> e : newRecords.entrySet()) {
            String newUri = e.getKey();
            String key = e.getValue().getFileKey();
            if (key == null || key.isBlank()) continue;
            String oldUri = oldByKey.get(key);
            if (oldUri != null && !oldUri.equals(newUri)) {
                renames.put(oldUri, newUri);
            }
        }
        return renames;
    }

    private static Map<String, PersistentVfsRecord> scanTree(FileObject root) throws IOException {
        Map<String, PersistentVfsRecord> out = new LinkedHashMap<>();

        if (!root.exists()) {
            return out;
        }

        URI rootUri = root.toUri();
        String scheme = rootUri.getScheme();
        if (scheme != null && scheme.equalsIgnoreCase("file")) {
            Path start = PersistentVfs.toLocalPath(rootUri);
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    add(dir, attrs);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    add(file, attrs);
                    return FileVisitResult.CONTINUE;
                }

                private void add(Path p, BasicFileAttributes attrs) {
                    URI uri = p.toUri();
                    String uriStr = PersistentVfs.normalizeUriString(uri);
                    boolean folder = attrs.isDirectory();
                    FileTime lm = attrs.lastModifiedTime();
                    long len = folder ? 0L : attrs.size();
                    String fileKey = PersistentVfs.tryGetLocalFileKey(uri, attrs);
                    out.put(uriStr, new PersistentVfsRecord(folder, lm != null ? lm.toMillis() : 0L, len, fileKey));
                }
            });
            return out;
        }

        List<FileObject> stack = new java.util.ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            FileObject fo = stack.remove(stack.size() - 1);
            if (fo == null) continue;
            fo.refresh();
            if (!fo.exists()) continue;

            String uriStr = PersistentVfs.normalizeUriString(fo.toUri());
            out.put(uriStr, recordOf(fo));

            if (fo.isFolder()) {
                List<FileObject> children = fo.getChildren();
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.add(children.get(i));
                }
            }
        }
        return out;
    }

    private static PersistentVfsRecord recordOf(FileObject fo) {
        boolean exists = fo != null && fo.exists();
        boolean folder = exists && fo.isFolder();
        long lm = exists ? fo.lastModified() : 0L;
        long len = exists ? fo.getLength() : 0L;

        String fileKey = null;
        if (exists) {
            URI uri = fo.toUri();
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(PersistentVfs.toLocalPath(uri), BasicFileAttributes.class);
                    fileKey = PersistentVfs.tryGetLocalFileKey(uri, attrs);
                } catch (Throwable ignored) {
                    // Best-effort.
                }
            }
        }

        return new PersistentVfsRecord(folder, lm, len, fileKey);
    }
}
