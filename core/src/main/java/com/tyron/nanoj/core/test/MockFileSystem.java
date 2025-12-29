package com.tyron.nanoj.core.test;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileEvent;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileSystem;
import com.tyron.nanoj.api.vfs.FileRenameEvent;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MockFileSystem implements FileSystem {

    private final Map<String, FileObject> memoryFiles = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<FileChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Helper to populate the "Disk"
     */
    public void registerFile(FileObject file) {
        memoryFiles.put(file.getPath(), file);
    }

    @Override
    public String getScheme() {
        // We hijack the "file" scheme so calls to VirtualFileSystem.find(new File(...)) 
        // come to us instead of the LocalFileSystem.
        return "file";
    }

    @Override
    public FileObject findResource(URI uri) {
        // URI file:///libs/Test.class -> Path /libs/Test.class
        return findResource(uri.getPath());
    }

    @Override
    public FileObject findResource(String path) {
        return memoryFiles.get(path);
    }

    @Override
    public void refresh(boolean asynchronous) {
        // No-op
    }

    @Override
    public void addFileChangeListener(FileChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeFileChangeListener(FileChangeListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    // --- Helpers for tests ---

    public void fireCreated(FileObject file) {
        FileEvent e = new FileEvent(file);
        for (FileChangeListener l : listeners) l.fileCreated(e);
    }

    public void fireDeleted(FileObject file) {
        FileEvent e = new FileEvent(file);
        for (FileChangeListener l : listeners) l.fileDeleted(e);
    }

    public void fireChanged(FileObject file) {
        FileEvent e = new FileEvent(file);
        for (FileChangeListener l : listeners) l.fileChanged(e);
    }

    public void fireRenamed(FileObject oldFile, FileObject newFile) {
        FileRenameEvent e = new FileRenameEvent(oldFile, newFile);
        for (FileChangeListener l : listeners) l.fileRenamed(e);
    }
}