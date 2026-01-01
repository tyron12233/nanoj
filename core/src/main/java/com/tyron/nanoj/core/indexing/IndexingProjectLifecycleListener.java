package com.tyron.nanoj.core.indexing;

import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.project.ProjectLifecycleListener;
import com.tyron.nanoj.api.vfs.FileObject;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

/**
 * Starts indexing when the project is opened.
 */
public final class IndexingProjectLifecycleListener implements ProjectLifecycleListener {

    private final Project project;

    public IndexingProjectLifecycleListener(Project project) {
        this.project = project;
    }

    @Override
    public void projectOpened(Project project) {
        DumbService.DumbModeToken token = DumbService.getInstance().startDumbTask("Project Opened");
        CompletableFuture.runAsync(() -> {
            IndexingInputCollector fileObjects = new IndexingInputCollector(project);
            for (FileObject fileObject : fileObjects) {
                System.out.println();
            }
        }).thenRun(token::close);
    }

    @Override
    public void projectClosing(Project project) {

    }
}
