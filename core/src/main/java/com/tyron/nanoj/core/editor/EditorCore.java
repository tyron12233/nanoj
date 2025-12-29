package com.tyron.nanoj.core.editor;

import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.api.diagnostics.ErrorHighlightingService;
import com.tyron.nanoj.api.editor.EditorManager;
import com.tyron.nanoj.api.editor.FileDocumentManager;
import com.tyron.nanoj.api.project.ProjectLifecycleListener;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.core.concurrent.TaskSchedulerImpl;
import com.tyron.nanoj.core.diagnostics.ErrorHighlightingServiceImpl;
import com.tyron.nanoj.core.dumb.DumbCore;
import com.tyron.nanoj.core.indexing.IndexingProjectLifecycleListener;
import com.tyron.nanoj.core.service.ProjectServiceManager;

/**
 * Convenience registration for editor/document infrastructure.
 *
 * The project uses a simple service container; interfaces require explicit bindings.
 * Call this once per project during initialization.
 */
public final class EditorCore {

    private EditorCore() {
    }

    public static void register(Project project) {
        DumbCore.register(project);
        ProjectServiceManager.registerBindingIfAbsent(project, TaskScheduler.class, TaskSchedulerImpl.class);
        ProjectServiceManager.registerBinding(project, FileDocumentManager.class, FileDocumentManagerImpl.class);
        ProjectServiceManager.registerBinding(project, EditorManager.class, EditorManagerImpl.class);
        ProjectServiceManager.registerBindingIfAbsent(project, ErrorHighlightingService.class, ErrorHighlightingServiceImpl.class);

        // Core lifecycle listeners
        ProjectServiceManager.registerExtension(project, ProjectLifecycleListener.class, IndexingProjectLifecycleListener.class);
    }
}
