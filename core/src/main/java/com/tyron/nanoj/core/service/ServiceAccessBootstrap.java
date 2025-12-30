package com.tyron.nanoj.core.service;

import com.tyron.nanoj.api.service.ServiceAccessHolder;
import com.tyron.nanoj.api.plugins.ProjectPluginRegistry;
import com.tyron.nanoj.api.tasks.ProjectRegistry;
import com.tyron.nanoj.core.plugins.ProjectPluginRegistryImpl;
import com.tyron.nanoj.core.tasks.ProjectRegistryImpl;

/**
 * Installed via {@link Class#forName(String)} from {@code :api} when needed.
 */
public final class ServiceAccessBootstrap {

    static {
        ServiceAccessHolder.set(new CoreServiceAccess());

        // Application scoped bindings
        ApplicationServiceManager.registerBindingIfAbsent(ProjectRegistry.class, ProjectRegistryImpl.class);
        ApplicationServiceManager.registerBindingIfAbsent(ProjectPluginRegistry.class, ProjectPluginRegistryImpl.class);
    }

    private ServiceAccessBootstrap() {
    }
}
