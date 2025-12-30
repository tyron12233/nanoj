package com.tyron.nanoj.testFramework.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileEvent;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileRenameEvent;
import com.tyron.nanoj.api.vfs.FileSystem;
import com.tyron.nanoj.api.vfs.VirtualFileManager;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory {@link VirtualFileManager} for tests.
 * <p>
 * Supports basic scheme routing, event dispatch, and stable ids per URI.
 */
public final class TestVirtualFileManager implements VirtualFileManager {

    private final Map<String, FileSystem> fileSystemsByScheme = new ConcurrentHashMap<>();
    private final Map<String, FileChangeListener> bridgesByScheme = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<FileChangeListener> globalListeners = new CopyOnWriteArrayList<>();

    private final Map<String, FileObject> filesByPath = new ConcurrentHashMap<>();

    private final Map<String, Integer> uriToId = new ConcurrentHashMap<>();
    private final Map<Integer, String> idToUri = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    @Override
    public void register(FileSystem fs) {
        if (fs == null) throw new IllegalArgumentException("fs == null");
        String scheme = fs.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("FileSystem scheme is blank");
        }

        // Replace any existing FS under the same scheme.
        FileSystem previous = fileSystemsByScheme.put(scheme, fs);
        if (previous != null) {
            FileChangeListener bridge = bridgesByScheme.remove(scheme);
            if (bridge != null) {
                previous.removeFileChangeListener(bridge);
            }
        }

        FileChangeListener bridge = new FileChangeListener() {
            @Override public void fileCreated(FileEvent event) { fireFileCreated(event.getFile()); }
            @Override public void fileDeleted(FileEvent event) { fireFileDeleted(event.getFile()); }
            @Override public void fileChanged(FileEvent event) { fireFileChanged(event.getFile()); }
            @Override public void fileRenamed(FileEvent e) {
                if (e instanceof FileRenameEvent event) {
                    fireFileRenamed(event.getOldFile(), event.getFile());
                }
            }
        };

        bridgesByScheme.put(scheme, bridge);
        fs.addFileChangeListener(bridge);
    }

    @Override
    public void unregister(String scheme) {
        if (scheme == null || scheme.isBlank()) return;
        FileSystem fs = fileSystemsByScheme.remove(scheme);
        if (fs == null) return;
        FileChangeListener bridge = bridgesByScheme.remove(scheme);
        if (bridge != null) {
            fs.removeFileChangeListener(bridge);
        }
    }

    @Override
    public FileSystem getFileSystem(String scheme) {
        if (scheme == null || scheme.isBlank()) return null;
        return fileSystemsByScheme.get(scheme);
    }

    @Override
    public Set<String> getRegisteredSchemes() {
        return Collections.unmodifiableSet(fileSystemsByScheme.keySet());
    }

    @Override
    public void refreshAll(boolean asynchronous) {
        for (FileSystem fs : fileSystemsByScheme.values()) {
            fs.refresh(asynchronous);
        }
    }

    @Override
    public FileObject find(File file) {
        if (file == null) return null;
        return find(file.toURI());
    }

    @Override
    public FileObject find(URI uri) {
        if (uri == null) return null;

        // jar: URIs often contain nested schemes (e.g. jar:file:///...!/path).
        // Delegate to the registered scheme FS if present.
        String scheme = uri.getScheme();
        if (scheme != null) {
            FileSystem fs = fileSystemsByScheme.get(scheme);
            if (fs != null) {
                FileObject fo = fs.findResource(uri);
                if (fo != null) {
                    indexFile(fo);
                }
                return fo;
            }
        }

        // Fallback for file:// URIs without an FS registered.
        String path = uri.getPath();
        if (path == null) return null;
        return filesByPath.get(path);
    }

    @Override
    public FileObject find(String pathOrUri) {
        if (pathOrUri == null || pathOrUri.isBlank()) return null;
        String trimmed = pathOrUri.trim();

        // Treat as URI if it looks like one.
        int schemeSep = trimmed.indexOf("://");
        if (schemeSep > 0) {
            return find(URI.create(trimmed));
        }

        // Otherwise treat as file path.
        return find(new File(trimmed));
    }

    @Override
    public int getFileId(FileObject file) {
        if (file == null) return 0;
        String uri = file.toUri().toString();
        return uriToId.computeIfAbsent(uri, k -> {
            int id = nextId.getAndIncrement();
            idToUri.put(id, k);
            return id;
        });
    }

    @Override
    public FileObject findById(int id) {
        if (id <= 0) return null;
        String uri = idToUri.get(id);
        if (uri == null) return null;
        return find(URI.create(uri));
    }

    @Override
    public void trackRoot(FileObject root) {
        // No-op for tests.
    }

    @Override
    public void addGlobalListener(FileChangeListener l) {
        if (l == null) throw new IllegalArgumentException("l == null");
        globalListeners.add(l);
    }

    @Override
    public void removeGlobalListener(FileChangeListener l) {
        if (l == null) return;
        globalListeners.remove(l);
    }

    @Override
    public void fireFileCreated(FileObject fo) {
        if (fo == null) return;
        indexFile(fo);
        FileEvent e = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileCreated(e);
    }

    @Override
    public void fireFileDeleted(FileObject fo) {
        if (fo == null) return;
        unindexFile(fo);
        FileEvent e = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileDeleted(e);
    }

    @Override
    public void fireFileChanged(FileObject fo) {
        if (fo == null) return;
        indexFile(fo);
        FileEvent e = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileChanged(e);
    }

    @Override
    public void fireFileRenamed(FileObject oldFile, FileObject newFile) {
        if (oldFile == null || newFile == null) return;
        unindexFile(oldFile);
        indexFile(newFile);
        FileRenameEvent e = new FileRenameEvent(oldFile, newFile);
        for (FileChangeListener l : globalListeners) l.fileRenamed(e);
    }

    @Override
    public void clear() {
        fileSystemsByScheme.clear();
        bridgesByScheme.clear();
        globalListeners.clear();
        filesByPath.clear();
        uriToId.clear();
        idToUri.clear();
        nextId.set(1);
    }

    // --- Test helpers ---

    public void registerFile(FileObject file) {
        if (file == null) throw new IllegalArgumentException("file == null");
        indexFile(file);
    }

    public void unregisterFile(FileObject file) {
        if (file == null) return;
        unindexFile(file);
    }

    private void indexFile(FileObject file) {
        if (file == null) return;

        String path = file.getPath();
        if (path != null) {
            filesByPath.put(path, file);
        }

        URI uri = file.toUri();
        if (uri != null && uri.getPath() != null) {
            filesByPath.put(uri.getPath(), file);
        }

        getFileId(file);
    }

    private void unindexFile(FileObject file) {
        if (file == null) return;

        String path = file.getPath();
        if (path != null) {
            filesByPath.remove(path);
        }

        URI uri = file.toUri();
        if (uri != null && uri.getPath() != null) {
            filesByPath.remove(uri.getPath());
        }
    }
}
