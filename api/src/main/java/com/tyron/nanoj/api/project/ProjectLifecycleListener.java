package com.tyron.nanoj.api.project;

/**
 * Project lifecycle callback.
 * <p>
 * Fired by the platform when a project is fully configured and opened.
 */
public interface ProjectLifecycleListener {

    /**
     * Called when the project is opened and ready.
     */
    void projectOpened(Project project);

    /**
     * Called when the project is about to be closed.
     */
    default void projectClosing(Project project) {
    }
}
