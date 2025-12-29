package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileSystem;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.net.URI;
import java.util.Set;

/**
 * Naming alias for {@link VirtualFileSystem}.
 *
 * This exists so code and documentation can refer to the component as a "Virtual File Manager"
 * (it is primarily a registry/router and event bridge), while preserving the existing
 * {@code VirtualFileSystem} type and call sites.
 */
public final class VirtualFileManager {

    private static final VirtualFileManager INSTANCE = new VirtualFileManager();

    public static VirtualFileManager getInstance() {
        return INSTANCE;
    }

    private final VirtualFileSystem delegate;

    private VirtualFileManager() {
        this.delegate = VirtualFileSystem.getInstance();
    }

    public void register(FileSystem fs) {
        delegate.register(fs);
    }

    public void unregister(String scheme) {
        delegate.unregister(scheme);
    }

    public FileSystem getFileSystem(String scheme) {
        return delegate.getFileSystem(scheme);
    }

    public Set<String> getRegisteredSchemes() {
        return delegate.getRegisteredSchemes();
    }

    public FileObject find(File file) {
        return delegate.find(file);
    }

    public FileObject find(URI uri) {
        return delegate.find(uri);
    }

    public FileObject find(String pathOrUri) {
        return delegate.find(pathOrUri);
    }

    public void addGlobalListener(FileChangeListener l) {
        delegate.addGlobalListener(l);
    }

    public void removeGlobalListener(FileChangeListener l) {
        delegate.removeGlobalListener(l);
    }

    public void fireFileCreated(FileObject fo) {
        delegate.fireFileCreated(fo);
    }

    public void fireFileDeleted(FileObject fo) {
        delegate.fireFileDeleted(fo);
    }

    public void fireFileChanged(FileObject fo) {
        delegate.fireFileChanged(fo);
    }

    public void fireFileRenamed(FileObject oldFile, FileObject newFile) {
        delegate.fireFileRenamed(oldFile, newFile);
    }

    @TestOnly
    public void clear() {
        delegate.clear();
    }
}
