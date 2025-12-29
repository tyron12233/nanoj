package com.tyron.nanoj.core.project;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.project.ProjectLifecycleListener;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.util.List;

/**
 * Simple project lifecycle event dispatcher.
 */
public final class ProjectLifecycle {

    private ProjectLifecycle() {
    }

    public static void fireProjectOpened(Project project) {
        List<ProjectLifecycleListener> listeners = ProjectServiceManager.getExtensions(project, ProjectLifecycleListener.class);
        for (ProjectLifecycleListener l : listeners) {
            try {
                l.projectOpened(project);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public static void fireProjectClosing(Project project) {
        List<ProjectLifecycleListener> listeners = ProjectServiceManager.getExtensions(project, ProjectLifecycleListener.class);
        for (ProjectLifecycleListener l : listeners) {
            try {
                l.projectClosing(project);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}
