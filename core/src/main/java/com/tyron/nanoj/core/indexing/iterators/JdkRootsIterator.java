package com.tyron.nanoj.core.indexing.iterators;

import com.tyron.nanoj.api.indexing.iterators.IndexableFilesIterator;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;

import java.util.function.Consumer;

public class JdkRootsIterator implements IndexableFilesIterator {

    private final Project project;

    public JdkRootsIterator(Project project) {
        this.project = project;
    }

    @Override
    public String getDebugName() {
        return "JDK ROOTS";
    }

    @Override
    public void iterateRoots(Consumer<FileObject> consumer) {
        consumer.accept(
                VirtualFileManager.getInstance().find("jrt://modules")
        );
    }
}
