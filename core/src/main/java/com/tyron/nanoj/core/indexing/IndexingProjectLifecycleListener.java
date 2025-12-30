package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.project.ProjectLifecycleListener;

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
        // Use the instance for this project (same as the parameter) to avoid surprises.
        IndexManager manager = IndexManager.getInstance(this.project);
        manager.onProjectOpened();

        // Trigger initial scan input submission outside IndexManager.
        try {
            new IndexingInputCollector(this.project, manager).submitAll();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void projectClosing(Project project) {
        // No-op for now.
    }
}
