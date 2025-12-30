package com.tyron.nanoj.core.service;

import com.tyron.nanoj.api.project.Project;

/**
 * Context passed to a {@link ServiceContainer}.
 * <p>
 * This is intentionally minimal today, but is designed to evolve as NanoJ adds more scopes
 * (application, project, module, etc.).
 */
public final class ServiceContext {

    private final Project project;

    private ServiceContext(Project project) {
        this.project = project;
    }

    /**
     * Context for a project-scoped container.
     */
    public static ServiceContext project(Project project) {
        if (project == null) throw new IllegalArgumentException("project == null");
        return new ServiceContext(project);
    }

    /**
     * Context for an application-scoped container.
     */
    public static ServiceContext application() {
        return new ServiceContext(null);
    }

    /**
     * @return The project for project-scoped containers, or null for application-scoped containers.
     */
    public Project getProjectOrNull() {
        return project;
    }
}
