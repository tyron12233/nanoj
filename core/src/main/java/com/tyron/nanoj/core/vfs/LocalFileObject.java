package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileObjectWithId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Concrete implementation of FileObject using java.nio.path.
 */
public class LocalFileObject implements FileObject {

    private final LocalFileSystem fs;
    private final Path path;

    LocalFileObject(LocalFileSystem fs, Path path) {
        this.fs = fs;
        this.path = path.toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        Path p = path.getFileName();
        return p != null ? p.toString() : "";
    }

    @Override
    public String getExtension() {
        String name = getName();
        int dot = name.lastIndexOf('.');
        return (dot > -1 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
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
        Path p = path.getParent();
        return p != null ? new LocalFileObject(fs, p) : null;
    }

    @Override
    public List<FileObject> getChildren() {
        if (!isFolder()) return Collections.emptyList();
        try (Stream<Path> s = Files.list(path)) {
            return s.map(p -> new LocalFileObject(fs, p)).collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public FileObject getChild(String name) {
        return new LocalFileObject(fs, path.resolve(name));
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
        // Return a wrapper that notifies on close
        return new OutputStream() {
            private final OutputStream delegate = Files.newOutputStream(path);
            
            @Override
            public void write(int b) throws IOException { delegate.write(b); }
            @Override
            public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); }
            
            @Override
            public void close() throws IOException {
                delegate.close();
                // Notify that content changed
                fs.notifyChanged(LocalFileObject.this);
            }
        };
    }

    @Override
    public FileObject createFile(String name) throws IOException {
        if (!isFolder()) throw new IOException("Not a folder: " + path);
        Path child = path.resolve(name);
        Files.createFile(child);
        LocalFileObject newFile = new LocalFileObject(fs, child);
        fs.notifyCreated(newFile);
        return newFile;
    }

    @Override
    public FileObject createFolder(String name) throws IOException {
        if (!isFolder()) throw new IOException("Not a folder: " + path);
        Path child = path.resolve(name);
        Files.createDirectory(child);
        LocalFileObject newDir = new LocalFileObject(fs, child);
        fs.notifyCreated(newDir);
        return newDir;
    }

    @Override
    public void delete() throws IOException {
        // Files.delete() throws DirectoryNotEmptyException if not empty.
        // We rely on the caller (FileUtil.deleteRecursively) to handle children.
        // Or we strictly implement the interface contract.
        // Standard contract: delete() fails if folder not empty.
        Files.delete(path);
        fs.notifyDeleted(this);
    }

    @Override
    public void rename(String newName) throws IOException {
        Path target = path.resolveSibling(newName);
        Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
        // FileObject instances are immutable; after rename, this instance points at the old path.
        // Notify rename (for consumers that care) and also delete/create (for legacy consumers).
        LocalFileObject newObj = new LocalFileObject(fs, target);
        fs.notifyRenamed(this, newObj);
        fs.notifyDeleted(this);
        fs.notifyCreated(newObj);
    }

    @Override
    public void refresh() {
        // No-op for NIO
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LocalFileObject that = (LocalFileObject) o;
        return path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
    
    @Override
    public String toString() {
        return path.toString();
    }
}