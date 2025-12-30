package com.tyron.nanoj.api.tasks;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.ServiceAccessHolder;

import java.util.Objects;

/**
 * Application-scoped registry for resolving open {@link Project}s by id.
 */
public interface ProjectRegistry {

    static ProjectRegistry getInstance() {
        return ServiceAccessHolder.get().getApplicationService(ProjectRegistry.class);
    }

    /**
     * Registers a project with a stable id.
     */
    void register(String projectId, Project project);

    /**
     * Convenience: registers using {@link Project#getName()} as id.
     */
    default void register(Project project) {
        Objects.requireNonNull(project, "project");
        register(project.getName(), project);
    }

    /**
     * Removes a previously registered project id.
     */
    void unregister(String projectId);

    /**
     * @return the project for id, or {@code null}.
     */
    Project getProject(String projectId);

    /**
     * @throws IllegalStateException if project id is not registered.
     */
    default Project requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is blank");
        }
        Project project = getProject(projectId.trim());
        if (project == null) {
            throw new IllegalStateException("Project not registered: " + projectId);
        }
        return project;
    }
}
