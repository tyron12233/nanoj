package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileSystem;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Read-only {@code jrt:} filesystem backed by the JDK module runtime image.
 *
 * Supports URIs like:
 * - {@code jrt:/modules/java.base/java/lang/String.class}
 */
public final class JrtFileSystem implements FileSystem {

    private static final JrtFileSystem INSTANCE = new JrtFileSystem();

    public static JrtFileSystem getInstance() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<FileChangeListener> listeners = new CopyOnWriteArrayList<>();

    private static volatile java.nio.file.FileSystem JRT_NIO_FS;

    private JrtFileSystem() {
    }

    @Override
    public String getScheme() {
        return "jrt";
    }

    @Override
    public FileObject findResource(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!"jrt".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported scheme for JrtFileSystem: " + uri);
        }

        java.nio.file.FileSystem jrt = getOrCreateJrtNioFs();
        String p = uri.getPath();
        if (p == null || p.isBlank()) {
            p = "/";
        }
        Path path = jrt.getPath(p);
        return JrtFileObject.of(path);
    }

    @Override
    public FileObject findResource(String path) {
        Objects.requireNonNull(path, "path");
        if (path.startsWith("jrt:")) {
            return findResource(URI.create(path));
        }

        java.nio.file.FileSystem jrt = getOrCreateJrtNioFs();
        String p = path;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return JrtFileObject.of(jrt.getPath(p));
    }

    @Override
    public void refresh(boolean asynchronous) {
        // No-op; runtime image is immutable.
    }

    @Override
    public void addFileChangeListener(FileChangeListener listener) {
        // jrt: is immutable, but keep API symmetry.
        listeners.add(listener);
    }

    @Override
    public void removeFileChangeListener(FileChangeListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    public static java.nio.file.FileSystem getOrCreateJrtNioFs() {
        java.nio.file.FileSystem cached = JRT_NIO_FS;
        if (cached != null) {
            return cached;
        }

        synchronized (JrtFileSystem.class) {
            cached = JRT_NIO_FS;
            if (cached != null) {
                return cached;
            }

            URI root = URI.create("jrt:/");
            try {
                cached = FileSystems.getFileSystem(root);
            } catch (Throwable ignored) {
                try {
                    cached = FileSystems.newFileSystem(root, Map.of());
                } catch (Throwable t) {
                    // jrt: not supported (JDK8 or stripped runtime)
                    throw new UnsupportedOperationException("jrt: filesystem is not available in this runtime", t);
                }
            }
            JRT_NIO_FS = cached;
            return cached;
        }
    }
}
