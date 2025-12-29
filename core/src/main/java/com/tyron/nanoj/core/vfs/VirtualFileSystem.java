package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileEvent;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileSystem;
import com.tyron.nanoj.api.vfs.FileRenameEvent;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The central registry for File Systems.
 * <p>
 * Usage:
 * <pre>
 *     FileObject fo = VirtualFileSystem.getInstance().find(new File("/sdcard/..."));
 * </pre>
 */
public class VirtualFileSystem {

    private static final VirtualFileSystem INSTANCE = new VirtualFileSystem();

    public static VirtualFileSystem getInstance() {
        return INSTANCE;
    }

    // Map Scheme -> FileSystem ("file" -> LocalFileSystem)
    private final Map<String, FileSystem> registry = new ConcurrentHashMap<>();

    // FileSystem -> Bridge listener (for cleanup when FS is replaced/unregistered)
    private final Map<FileSystem, FileChangeListener> bridges = new ConcurrentHashMap<>();
    
    // Global listeners
    private final CopyOnWriteArrayList<FileChangeListener> globalListeners = new CopyOnWriteArrayList<>();

    private VirtualFileSystem() {
        // Register default Local NIO FS
        register(LocalFileSystem.getInstance());

        // Register read-only jar filesystem
        register(JarFileSystem.getInstance());

        // Register read-only jrt filesystem (JDK modules)
        register(JrtFileSystem.getInstance());
    }

    /**
     * Registers a file system. If another FS was already registered for the same scheme,
     * it is replaced.
     *
     * The VFS installs an internal bridge listener on the FS so that FS-local events are
     * propagated to VFS-global listeners.
     */
    public void register(FileSystem fs) {
        Objects.requireNonNull(fs, "fs");

        String scheme = normalizeScheme(fs.getScheme());
        FileSystem previous = registry.put(scheme, fs);
        if (previous != null) {
            detachBridge(previous);
        }

        attachBridge(fs);
    }

    /**
     * Unregisters a filesystem by scheme.
     */
    public void unregister(String scheme) {
        String key = normalizeScheme(scheme);
        FileSystem removed = registry.remove(key);
        if (removed != null) {
            detachBridge(removed);
        }
    }

    public FileSystem getFileSystem(String scheme) {
        return registry.get(normalizeScheme(scheme));
    }

    public Set<String> getRegisteredSchemes() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * Entry point to convert Java IO File to VFS FileObject.
     */
    public FileObject find(File file) {
        return find(file.toURI());
    }

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

    public FileObject find(String pathOrUri) {
        Objects.requireNonNull(pathOrUri, "pathOrUri");

        // Very small helper: if it looks like a URI, try URI parsing; otherwise treat as a local path.
        int schemeIdx = pathOrUri.indexOf("://");
        if (schemeIdx > 0) {
            return find(URI.create(pathOrUri));
        }
        return find(new File(pathOrUri));
    }

    public void fireFileCreated(FileObject fo) {
        FileEvent event = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileCreated(event);
    }

    public void fireFileDeleted(FileObject fo) {
        FileEvent event = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileDeleted(event);
    }

    public void fireFileChanged(FileObject fo) {
        FileEvent event = new FileEvent(fo);
        for (FileChangeListener l : globalListeners) l.fileChanged(event);
    }

    public void fireFileRenamed(FileObject oldFile, FileObject newFile) {
        FileRenameEvent event = new FileRenameEvent(oldFile, newFile);
        for (FileChangeListener l : globalListeners) l.fileRenamed(event);
    }

    private void fireFileRenamed(FileEvent event) {
        for (FileChangeListener l : globalListeners) l.fileRenamed(event);
    }
    
    public void addGlobalListener(FileChangeListener l) {
        globalListeners.add(l);
    }
    
    public void removeGlobalListener(FileChangeListener l) {
        globalListeners.remove(l);
    }

    @TestOnly
    public void clear() {
        for (FileSystem fs : registry.values()) {
            detachBridge(fs);
        }
        registry.clear();
        globalListeners.clear();

        // Restore default FS after reset.
        register(LocalFileSystem.getInstance());

        // Restore jar filesystem after reset.
        register(JarFileSystem.getInstance());

        // Restore jrt filesystem after reset.
        register(JrtFileSystem.getInstance());
    }

    private void attachBridge(FileSystem fs) {
        FileChangeListener bridge = new FileChangeListener() {
            @Override
            public void fileCreated(FileEvent event) {
                fireFileCreated(event.getFile());
            }

            @Override
            public void fileDeleted(FileEvent event) {
                fireFileDeleted(event.getFile());
            }

            @Override
            public void fileChanged(FileEvent event) {
                fireFileChanged(event.getFile());
            }

            @Override
            public void fileRenamed(FileEvent event) {
                // Preserve richer event type if provided by FS.
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
}