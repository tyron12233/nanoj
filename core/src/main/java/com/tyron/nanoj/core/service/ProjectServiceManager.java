package com.tyron.nanoj.core.service;

import com.tyron.nanoj.api.project.Project;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The central Service Locator and Dependency Injection container for the IDE.
 * <p>
 * <b>Services:</b> Singleton per project. Used for core logic (FileManager, Indexer).
 * <p>
 * <b>Extensions:</b> Multiple instances per interface. Used for plugins (LanguageSupport, FileTypeDetector).
 */
public class ProjectServiceManager {
    private static final Map<Project, ServiceContainer> projectContainers = new ConcurrentHashMap<>();

    public static <T> T getService(Project project, Class<T> serviceClass) {
        checkOpen(project);
        return getContainer(project).getService(serviceClass);
    }

    public static <T, I extends T> void registerBinding(Project project, Class<T> interfaceClass, Class<I> implementationClass) {
        getContainer(project).registerBinding(interfaceClass, implementationClass);
    }

    /**
     * Registers a binding only if no binding exists yet and the service was not instantiated.
     * Useful for core services that may be registered from multiple entry points.
     */
    public static <T, I extends T> void registerBindingIfAbsent(Project project, Class<T> interfaceClass, Class<I> implementationClass) {
        getContainer(project).registerBindingIfAbsent(interfaceClass, implementationClass);
    }

    public static <T> void registerInstance(Project project, Class<T> serviceClass, T instance) {
        getContainer(project).registerInstance(serviceClass, instance);
    }

    /**
     * Registers an implementation for a specific extension point.
     * <p>
     * Example:
     * <pre>
     * registerExtension(project, LanguageSupport.class, JavaLanguageSupport.class);
     * registerExtension(project, LanguageSupport.class, XmlLanguageSupport.class);
     * </pre>
     *
     * @param project The project scope.
     * @param extensionPoint The interface class (e.g., LanguageSupport.class).
     * @param extensionImpl The implementation class (e.g., JavaLanguageSupport.class).
     */
    public static <E, I extends E> void registerExtension(Project project, Class<E> extensionPoint, Class<I> extensionImpl) {
        getContainer(project).registerExtension(extensionPoint, extensionImpl);
    }

    /**
     * Retrieves all registered extensions for a given interface.
     * <p>
     * The instances are created lazily upon the first call to this method.
     * </p>
     *
     * @param project The project scope.
     * @param extensionPoint The interface class.
     * @return An unmodifiable list of instantiated extensions.
     */
    public static <E> List<E> getExtensions(Project project, Class<E> extensionPoint) {
        checkOpen(project);
        return getContainer(project).getExtensions(extensionPoint);
    }

    public static void disposeProject(Project project) {
        ServiceContainer container = projectContainers.remove(project);
        if (container != null) {
            container.disposeAll();
        }
    }

    private static void checkOpen(Project project) {
        if (!project.isOpen()) {
            throw new IllegalStateException("Cannot access services for a closed project: " + project.getName());
        }
    }

    private static ServiceContainer getContainer(Project project) {
        return projectContainers.computeIfAbsent(project, p ->
            new DefaultServiceContainer(ServiceContext.project(p)));
    }
}