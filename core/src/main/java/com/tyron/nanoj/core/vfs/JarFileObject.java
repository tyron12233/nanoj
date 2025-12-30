package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileObjectWithId;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * FileObject inside a jar: filesystem.
 */
final class JarFileObject implements FileObject {

    private final URI jarUri;
    private final String entryPath; // "" for root, "p/" for directory, "p/A.class" for file
    private final JarFileSystem.JarIndex index;
    private final JarFileSystem fs;

    JarFileObject(URI jarUri, String entryPath, JarFileSystem.JarIndex index, JarFileSystem fs) {
        this.jarUri = Objects.requireNonNull(jarUri, "jarUri");
        this.entryPath = entryPath == null ? "" : entryPath;
        this.index = Objects.requireNonNull(index, "index");
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    @Override
    public String getName() {
        if (entryPath.isEmpty()) {
            return jarFileName();
        }
        return JarFileSystem.nameOf(entryPath);
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
        String normalized = JarFileSystem.normalizeEntryPath(entryPath);
        // Root should end with "!/" for consistency.
        if (normalized.isEmpty()) {
            return URI.create("jar:" + jarUri + "!/");
        }
        return URI.create("jar:" + jarUri + "!/" + normalized);
    }

    @Override
    public FileObject getParent() {
        if (entryPath.isEmpty()) {
            return null;
        }
        String parent = JarFileSystem.parentDir(entryPath);
        if (parent == null) {
            parent = "";
        }
        return new JarFileObject(jarUri, parent, index, fs);
    }

    @Override
    public List<FileObject> getChildren() {
        if (!isFolder()) {
            return Collections.emptyList();
        }

        String dir = JarFileSystem.normalizeDirPath(entryPath);
        List<String> kids = index.listChildren(dir);
        if (kids.isEmpty()) {
            return Collections.emptyList();
        }

        List<FileObject> result = new ArrayList<>(kids.size());
        for (String name : kids) {
            String childPath = dir.isEmpty() ? name : dir + name;
            // If it's a directory we stored it without trailing slash as child name; reconstruct.
            if (index.isDirectory(childPath + "/")) {
                childPath = childPath + "/";
            }
            result.add(new JarFileObject(jarUri, childPath, index, fs));
        }
        return result;
    }

    @Override
    public FileObject getChild(String name) {
        if (name == null) return null;
        if (!isFolder()) return null;

        String dir = JarFileSystem.normalizeDirPath(entryPath);
        String childPath = dir.isEmpty() ? name : dir + name;
        if (index.isDirectory(childPath + "/")) {
            return new JarFileObject(jarUri, childPath + "/", index, fs);
        }
        if (index.isFile(childPath)) {
            return new JarFileObject(jarUri, childPath, index, fs);
        }
        return null;
    }

    @Override
    public boolean exists() {
        if (entryPath.isEmpty()) {
            return true;
        }
        return index.isDirectory(entryPath) || index.isFile(entryPath);
    }

    @Override
    public boolean isFolder() {
        if (entryPath.isEmpty()) {
            return true;
        }
        return index.isDirectory(entryPath);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public long lastModified() {
        // Best-effort: jar file timestamp if available.
        if ("file".equalsIgnoreCase(jarUri.getScheme())) {
            try {
                return new File(jarUri).lastModified();
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    @Override
    public long getLength() {
        if (isFolder()) {
            return 0L;
        }
        try (InputStream in = getInputStream()) {
            return in.readAllBytes().length;
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (isFolder()) {
            throw new IOException("Cannot open InputStream for directory: " + getPath());
        }
        return index.openEntryStream(entryPath);
    }

    @Override
    public OutputStream getOutputStream() {
        throw new UnsupportedOperationException("jar: filesystem is read-only");
    }

    @Override
    public FileObject createFile(String name) {
        throw new UnsupportedOperationException("jar: filesystem is read-only");
    }

    @Override
    public FileObject createFolder(String name) {
        throw new UnsupportedOperationException("jar: filesystem is read-only");
    }

    @Override
    public void delete() {
        throw new UnsupportedOperationException("jar: filesystem is read-only");
    }

    @Override
    public void rename(String newName) {
        throw new UnsupportedOperationException("jar: filesystem is read-only");
    }

    @Override
    public void refresh() {
        // no-op
    }

    private String jarFileName() {
        String s = jarUri.toString();
        int idx = s.lastIndexOf('/');
        return idx < 0 ? s : s.substring(idx + 1);
    }
}
