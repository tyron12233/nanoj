package com.tyron.nanoj.core.service;

import java.util.List;

/**
 * Application-scoped service container.
 * <p>
 * This is the root scope in the service hierarchy.
 */
public final class ApplicationServiceManager {

    private static final ServiceContainer container =
            new DefaultServiceContainer(ServiceContext.application());

    private ApplicationServiceManager() {
    }

    static ServiceContainer getContainer() {
        return container;
    }

    public static <T> T getService(Class<T> serviceClass) {
        return container.getService(serviceClass);
    }

    public static <T, I extends T> void registerBinding(Class<T> interfaceClass, Class<I> implementationClass) {
        container.registerBinding(interfaceClass, implementationClass);
    }

    public static <T, I extends T> void registerBindingIfAbsent(Class<T> interfaceClass, Class<I> implementationClass) {
        container.registerBindingIfAbsent(interfaceClass, implementationClass);
    }

    public static <T> void registerInstance(Class<T> serviceClass, T instance) {
        container.registerInstance(serviceClass, instance);
    }

    public static <E, I extends E> void registerExtension(Class<E> extensionPoint, Class<I> extensionImpl) {
        container.registerExtension(extensionPoint, extensionImpl);
    }

    public static <E> List<E> getExtensions(Class<E> extensionPoint) {
        return container.getExtensions(extensionPoint);
    }

    /**
     * Disposes all application-scoped services and extensions.
     * <p>
     * Note: project containers are disposed separately via {@link ProjectServiceManager#disposeProject}.
     */
    public static void disposeApplication() {
        container.disposeAll();

        // Reinstall default bindings after clearing.
        // ServiceAccessBootstrap static initializer won't run again once loaded.
        ServiceAccessBootstrap.installDefaultApplicationBindings();
    }
}
