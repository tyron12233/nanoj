package com.tyron.nanoj.api.vfs;

import com.tyron.nanoj.api.service.ServiceAccessHolder;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.net.URI;
import java.util.Set;

/**
 * IntelliJ-inspired Virtual File Manager (application-scoped).
 * <p>
 * This is an API surface; the implementation lives in {@code :core}.
 */
public interface VirtualFileManager {

    static VirtualFileManager getInstance() {
        return ServiceAccessHolder.get().getApplicationService(VirtualFileManager.class);
    }

    void register(FileSystem fs);

    void unregister(String scheme);

    FileSystem getFileSystem(String scheme);

    Set<String> getRegisteredSchemes();

    void refreshAll(boolean asynchronous);

    FileObject find(File file);

    FileObject find(URI uri);

    FileObject find(String pathOrUri);

    int getFileId(FileObject file);

    FileObject findById(int id);

    void trackRoot(FileObject root);

    void addGlobalListener(FileChangeListener l);

    void removeGlobalListener(FileChangeListener l);

    void fireFileCreated(FileObject fo);

    void fireFileDeleted(FileObject fo);

    void fireFileChanged(FileObject fo);

    void fireFileRenamed(FileObject oldFile, FileObject newFile);

    @TestOnly
    void clear();
}
