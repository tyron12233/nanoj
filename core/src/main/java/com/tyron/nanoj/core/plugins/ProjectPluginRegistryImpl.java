package com.tyron.nanoj.core.plugins;

import com.tyron.nanoj.api.plugins.ProjectPluginFactory;
import com.tyron.nanoj.api.plugins.ProjectPluginRegistry;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Default application-scoped implementation of {@link ProjectPluginRegistry}.
 */
public final class ProjectPluginRegistryImpl implements ProjectPluginRegistry {

    private final ConcurrentHashMap<String, ProjectPluginFactory> factoriesById = new ConcurrentHashMap<>();

    @Override
    public void register(String pluginId, ProjectPluginFactory factory) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId is blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory is null");
        }
        factoriesById.put(pluginId.trim(), factory);
    }

    @Override
    public void unregister(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return;
        factoriesById.remove(pluginId.trim());
    }

    @Override
    public ProjectPluginFactory getFactory(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return null;
        return factoriesById.get(pluginId.trim());
    }
}
