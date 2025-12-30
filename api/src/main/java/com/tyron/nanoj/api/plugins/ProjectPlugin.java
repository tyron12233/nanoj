package com.tyron.nanoj.api.plugins;

/**
 * A Gradle-like plugin that can configure a {@link com.tyron.nanoj.api.project.Project}.
 *
 * <p>Plugins are applied during project open, after project configuration is loaded.
 * Plugins typically register tasks and set project configuration defaults.
 */
public interface ProjectPlugin {

    /**
     * Stable plugin id, e.g. {@code "java-library"}.
     */
    String getId();

    /**
     * Applies this plugin to the project.
     */
    void apply(ProjectPluginContext context) throws Exception;
}
