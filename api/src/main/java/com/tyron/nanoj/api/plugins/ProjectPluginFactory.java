package com.tyron.nanoj.api.plugins;

import com.tyron.nanoj.api.project.Project;

/**
 * Factory for creating (optionally project-scoped) plugin instances.
 */
@FunctionalInterface
public interface ProjectPluginFactory {

    ProjectPlugin create(Project project);
}
