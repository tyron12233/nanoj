package com.tyron.nanoj.api.plugins;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.ServiceAccessHolder;

import java.util.Objects;

/**
 * Application-scoped registry of available plugins.
 */
public interface ProjectPluginRegistry {

    static ProjectPluginRegistry getInstance() {
        return ServiceAccessHolder.get().getApplicationService(ProjectPluginRegistry.class);
    }

    /**
     * Registers a plugin factory under a stable id.
     */
    void register(String pluginId, ProjectPluginFactory factory);

    /**
     * Convenience for registering a stateless plugin instance.
     */
    default void register(ProjectPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        register(plugin.getId(), p -> plugin);
    }

    void unregister(String pluginId);

    /**
     * @return a factory for {@code pluginId}, or {@code null}.
     */
    ProjectPluginFactory getFactory(String pluginId);

    default ProjectPluginFactory requireFactory(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId is blank");
        }
        ProjectPluginFactory f = getFactory(pluginId.trim());
        if (f == null) {
            throw new IllegalStateException("Plugin not registered: " + pluginId);
        }
        return f;
    }

    default ProjectPlugin create(Project project, String pluginId) {
        Objects.requireNonNull(project, "project");
        return requireFactory(pluginId).create(project);
    }
}
