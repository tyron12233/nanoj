package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileEvent;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileSystem;
import com.tyron.nanoj.api.vfs.FileRenameEvent;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A FileSystem implementation backed by java.nio.
 * Handles "file://" URI schemes.
 */
public class LocalFileSystem implements FileSystem {

    private static final LocalFileSystem INSTANCE = new LocalFileSystem();

    public static LocalFileSystem getInstance() {
        return INSTANCE;
    }

    private LocalFileSystem() {

    }

    private final CopyOnWriteArrayList<FileChangeListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public String getScheme() {
        return "file";
    }

    @Override
    public FileObject findResource(URI uri) {
        if (!"file".equals(uri.getScheme())) {
            throw new IllegalArgumentException("LocalFileSystem only supports file:// URIs");
        }
        return new LocalFileObject(this, Paths.get(uri));
    }

    @Override
    public FileObject findResource(String path) {
        return new LocalFileObject(this, Paths.get(path));
    }

    @Override
    public void refresh(boolean asynchronous) {
        // NIO is direct-to-disk, so explicit refresh isn't usually needed
        // unless we implement an internal caching layer later.
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public void addFileChangeListener(FileChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeFileChangeListener(FileChangeListener listener) {
        listeners.remove(listener);
    }

    void notifyCreated(FileObject fo) {
        FileEvent e = new FileEvent(fo);
        for (FileChangeListener l : listeners) l.fileCreated(e);
    }

    void notifyDeleted(FileObject fo) {
        FileEvent e = new FileEvent(fo);
        for (FileChangeListener l : listeners) l.fileDeleted(e);
    }

    void notifyChanged(FileObject fo) {
        FileEvent e = new FileEvent(fo);
        for (FileChangeListener l : listeners) l.fileChanged(e);
    }

    void notifyRenamed(FileObject oldFile, FileObject newFile) {
        FileRenameEvent e = new FileRenameEvent(oldFile, newFile);
        for (FileChangeListener l : listeners) l.fileRenamed(e);
    }
}