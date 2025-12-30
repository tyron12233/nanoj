package com.tyron.nanoj.core.indexing.iterators;

import com.tyron.nanoj.api.indexing.iterators.IndexableFilesIterator;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;

import java.util.List;
import java.util.function.Consumer;

final class ProjectSourceRootsIterator implements IndexableFilesIterator {

    private final Project project;

    ProjectSourceRootsIterator(Project project) {
        this.project = project;
    }

    @Override
    public String getDebugName() {
        return "ProjectSourceRoots";
    }

    @Override
    public void iterateRoots(Consumer<FileObject> consumer) {
        if (consumer == null) {
            return;
        }
        if (project == null || !project.isOpen()) {
            return;
        }

        List<FileObject> roots;
        try {
            roots = project.getSourceRoots();
        } catch (Throwable ignored) {
            roots = List.of();
        }

        if (roots == null || roots.isEmpty()) {
            return;
        }

        for (FileObject root : roots) {
            if (root == null) continue;
            consumer.accept(root);
        }
    }
}
