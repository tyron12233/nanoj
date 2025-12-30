package com.tyron.nanoj.core.service;

import com.tyron.nanoj.api.service.ServiceAccessHolder;
import com.tyron.nanoj.api.plugins.ProjectPluginRegistry;
import com.tyron.nanoj.api.tasks.ProjectRegistry;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.core.plugins.ProjectPluginRegistryImpl;
import com.tyron.nanoj.core.tasks.ProjectRegistryImpl;
import com.tyron.nanoj.core.vfs.VirtualFileManagerImpl;

/**
 * Installed via {@link Class#forName(String)} from {@code :api} when needed.
 */
public final class ServiceAccessBootstrap {

    static {
        ServiceAccessHolder.set(new CoreServiceAccess());

        installDefaultApplicationBindings();
    }

    /**
     * Re-installs application-scoped default bindings.
     * <p>
     * Tests may call {@link ApplicationServiceManager#disposeApplication()} which clears bindings.
     */
    public static void installDefaultApplicationBindings() {
        ApplicationServiceManager.registerBindingIfAbsent(ProjectRegistry.class, ProjectRegistryImpl.class);
        ApplicationServiceManager.registerBindingIfAbsent(ProjectPluginRegistry.class, ProjectPluginRegistryImpl.class);
        ApplicationServiceManager.registerBindingIfAbsent(VirtualFileManager.class, VirtualFileManagerImpl.class);
    }

    private ServiceAccessBootstrap() {
    }
}
