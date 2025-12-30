package com.tyron.nanoj.api.plugins;

import com.tyron.nanoj.api.project.Project;

import java.util.Objects;

/**
 * Plugin-scoped options view backed by {@link Project.ProjectConfiguration}.
 */
public final class ProjectPluginOptions {

    public static final String PREFIX = "nanoj.plugin.";
    public static final String OPTIONS_SEGMENT = ".option.";

    private final Project.ProjectConfiguration configuration;
    private final String pluginId;

    public ProjectPluginOptions(Project.ProjectConfiguration configuration, String pluginId) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId is blank");
        }
        this.pluginId = pluginId.trim();
    }

    public String getPluginId() {
        return pluginId;
    }

    public String get(String key) {
        return configuration.getProperty(toKey(key));
    }

    public String get(String key, String defaultValue) {
        return configuration.getProperty(toKey(key), defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key, null);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v);
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key, null);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public void set(String key, String value) {
        configuration.setProperty(toKey(key), value);
    }

    private String toKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is blank");
        }
        return PREFIX + pluginId + OPTIONS_SEGMENT + key.trim();
    }
}
