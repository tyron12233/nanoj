package com.tyron.nanoj.core.tasks;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.tasks.ProjectRegistry;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default application-scoped implementation of {@link ProjectRegistry}.
 */
public final class ProjectRegistryImpl implements ProjectRegistry {

    private final ConcurrentHashMap<String, Project> projectsById = new ConcurrentHashMap<>();

    @Override
    public void register(String projectId, Project project) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is blank");
        }
        Objects.requireNonNull(project, "project");
        projectsById.put(projectId.trim(), project);
    }

    @Override
    public void unregister(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        projectsById.remove(projectId.trim());
    }

    @Override
    public Project getProject(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        return projectsById.get(projectId.trim());
    }
}
