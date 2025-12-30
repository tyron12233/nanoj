package com.tyron.nanoj.api.plugins;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.tasks.TasksService;

/**
 * Context passed to {@link ProjectPlugin#apply(ProjectPluginContext)}.
 */
public interface ProjectPluginContext {

    Project getProject();

    /**
     * Convenience access to the project's tasks service.
     */
    TasksService getTasks();

    /**
     * Returns a configuration view scoped to the currently applying plugin.
     *
     * <p>Backed by {@link Project.ProjectConfiguration} keys under:
     * {@code nanoj.plugin.<pluginId>.option.*}
     */
    ProjectPluginOptions getOptions();
}
