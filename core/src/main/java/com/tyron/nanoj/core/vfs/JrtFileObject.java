package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileObjectWithId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class JrtFileObject implements FileObject {

    private static final ConcurrentHashMap<Path, JrtFileObject> INTERN = new ConcurrentHashMap<>();

    static JrtFileObject of(Path path) {
        Objects.requireNonNull(path, "path");
        return INTERN.computeIfAbsent(path, JrtFileObject::new);
    }

    private final Path path;

    private volatile Boolean existsCache;
    private volatile Boolean isFolderCache;
    private volatile Long lastModifiedCache;
    private volatile Long lengthCache;
    private volatile List<Path> childrenPathCache;

    private JrtFileObject(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public String getName() {
        Path name = path.getFileName();
        if (name == null) {
            return "jrt:/";
        }
        return name.toString();
    }

    @Override
    public String getExtension() {
        String name = getName();
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1);
    }

    @Override
    public String getPath() {
        return toUri().toString();
    }

    @Override
    public URI toUri() {
        // Avoid relying on Path#toUri() here; for some Path instances this can produce a relative
        // jrt URI (e.g. "jrt:modules/..."), which breaks callers that expect "jrt:/...".
        String p = path.toString();
        if (p == null || p.isBlank()) {
            p = "/";
        }
        p = p.replace('\\', '/');
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return URI.create("jrt:" + p);
    }

    @Override
    public FileObject getParent() {
        Path parent = path.getParent();
        if (parent == null) {
            return null;
        }
        return of(parent);
    }

    @Override
    public List<FileObject> getChildren() {
        if (!isFolder()) {
            return Collections.emptyList();
        }

        List<Path> cached = childrenPathCache;
        if (cached == null) {
            synchronized (this) {
                cached = childrenPathCache;
                if (cached == null) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                        List<Path> out = new ArrayList<>();
                        for (Path p : ds) {
                            out.add(p);
                        }
                        cached = out;
                    } catch (IOException e) {
                        cached = Collections.emptyList();
                    }
                    childrenPathCache = cached;
                }
            }
        }

        if (cached.isEmpty()) {
            return Collections.emptyList();
        }

        List<FileObject> result = new ArrayList<>(cached.size());
        for (Path p : cached) {
            result.add(of(p));
        }
        return result;
    }

    @Override
    public FileObject getChild(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return of(path.resolve(name));
    }

    @Override
    public boolean exists() {
        Boolean cached = existsCache;
        if (cached != null) {
            return cached;
        }
        boolean ex = Files.exists(path);
        existsCache = ex;
        return ex;
    }

    @Override
    public boolean isFolder() {
        Boolean cached = isFolderCache;
        if (cached != null) {
            return cached;
        }
        boolean dir = Files.isDirectory(path);
        isFolderCache = dir;
        return dir;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public long lastModified() {
        Long cached = lastModifiedCache;
        if (cached != null) {
            return cached;
        }
        long lm;
        try {
            lm = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            lm = 0L;
        }
        lastModifiedCache = lm;
        return lm;
    }

    @Override
    public long getLength() {
        Long cached = lengthCache;
        if (cached != null) {
            return cached;
        }
        long len;
        try {
            len = Files.size(path);
        } catch (IOException e) {
            len = 0L;
        }
        lengthCache = len;
        return len;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OutputStream getOutputStream() {
        throw new UnsupportedOperationException("jrt: filesystem is read-only");
    }

    @Override
    public FileObject createFile(String name) {
        throw new UnsupportedOperationException("jrt: filesystem is read-only");
    }

    @Override
    public FileObject createFolder(String name) {
        throw new UnsupportedOperationException("jrt: filesystem is read-only");
    }

    @Override
    public void delete() {
        throw new UnsupportedOperationException("jrt: filesystem is read-only");
    }

    @Override
    public void rename(String newName) {
        throw new UnsupportedOperationException("jrt: filesystem is read-only");
    }

    @Override
    public void refresh() {
        // No-op
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
