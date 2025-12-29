package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DiskFileObject implements FileObject {

    private final Path path;

    public DiskFileObject(FileObject parent, String child) {
        if (!(parent instanceof DiskFileObject)) {
            throw new IllegalArgumentException("Parent must be a DiskFileObject");
        }
        this.path = ((DiskFileObject) parent).path.resolve(child);
    }

    public DiskFileObject(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public DiskFileObject(java.io.File file) {
        this(file.toPath());
    }

    @Override
    public String getName() {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    @Override
    public String getExtension() {
        String name = getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && lastDot < name.length() - 1) {
            return name.substring(lastDot + 1);
        }
        return "";
    }

    @Override
    public String getPath() {
        return path.toString();
    }

    @Override
    public URI toUri() {
        return path.toUri();
    }

    @Override
    public FileObject getParent() {
        Path parent = path.getParent();
        return parent == null ? null : new DiskFileObject(parent);
    }

    @Override
    public List<FileObject> getChildren() {
        if (!isFolder()) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.list(path)) {
            return stream
                    .map(DiskFileObject::new)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            // In an IDE, we usually return empty list if permission denied or IO error occurs during listing
            // rather than crashing the file tree UI.
            return Collections.emptyList();
        }
    }

    @Override
    public FileObject getChild(String name) {
        return new DiskFileObject(path.resolve(name));
    }

    @Override
    public boolean exists() {
        return Files.exists(path);
    }

    @Override
    public boolean isFolder() {
        return Files.isDirectory(path);
    }

    @Override
    public boolean isReadOnly() {
        return !Files.isWritable(path);
    }

    @Override
    public long lastModified() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public long getLength() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        // Standard open options: create if missing, truncate if exists, write access
        return Files.newOutputStream(path);
    }

    @Override
    public FileObject createFile(String name) throws IOException {
        if (!isFolder()) {
            throw new UnsupportedOperationException("Cannot create file inside a file: " + path);
        }
        Path child = path.resolve(name);
        Files.createFile(child);
        return new DiskFileObject(child);
    }

    @Override
    public FileObject createFolder(String name) throws IOException {
        if (!isFolder()) {
            throw new UnsupportedOperationException("Cannot create folder inside a file: " + path);
        }
        Path child = path.resolve(name);
        Files.createDirectory(child);
        return new DiskFileObject(child);
    }

    @Override
    public void delete() throws IOException {
        Files.delete(path);
    }

    @Override
    public void rename(String newName) throws IOException {
        Path target = path.resolveSibling(newName);
        Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void refresh() {
        // Direct disk access implementation typically doesn't cache.
        // If we added a caching layer later, this would clear it.
    }

    // --- Object Identity ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiskFileObject that = (DiskFileObject) o;
        return path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return "DiskFileObject{" + path + "}";
    }
}