package com.tyron.nanoj.core.service;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
        return projectContainers.computeIfAbsent(project, ServiceContainer::new);
    }

    /**
     * Internal container holding the state for a single Project.
     */
    private static class ServiceContainer {
        private final Project project;

        private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
        private final Map<Class<?>, Class<?>> serviceBindings = new ConcurrentHashMap<>();
        private final Map<Class<?>, List<Class<?>>> extensionDefinitions = new ConcurrentHashMap<>();
        // Stored as Object because during creation we temporarily store a Pending placeholder.
        // Once created, the value is an immutable List<Object>.
        private final Map<Class<?>, Object> extensionCache = new ConcurrentHashMap<>();

        private final ThreadLocal<Deque<Class<?>>> instantiationStack = ThreadLocal.withInitial(ArrayDeque::new);

        private static final class Pending {
            final CompletableFuture<Object> future = new CompletableFuture<>();
        }

        ServiceContainer(Project project) {
            this.project = project;
        }

        @SuppressWarnings("unchecked")
        <T> T getService(Class<T> key) {
            return (T) getOrCreate(services, key, this::createServiceInstance);
        }

        <T, I extends T> void registerBinding(Class<T> interfaceClass, Class<I> implClass) {
            if (services.containsKey(interfaceClass)) {
                throw new IllegalStateException("Cannot bind " + interfaceClass.getName() + " because it is already instantiated.");
            }
            serviceBindings.put(interfaceClass, implClass);
        }

        <T, I extends T> void registerBindingIfAbsent(Class<T> interfaceClass, Class<I> implClass) {
            if (services.containsKey(interfaceClass)) {
                return;
            }
            serviceBindings.putIfAbsent(interfaceClass, implClass);
        }

        <T> void registerInstance(Class<T> serviceClass, T instance) {
            services.put(serviceClass, instance);
        }

        <E, I extends E> void registerExtension(Class<E> point, Class<I> impl) {
            // Invalidate cache if new extensions are added at runtime (optional, but safer)
            extensionCache.remove(point);

            extensionDefinitions.computeIfAbsent(point, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(impl);
        }

        @SuppressWarnings("unchecked")
        <E> List<E> getExtensions(Class<E> point) {
            return (List<E>) getOrCreate(extensionCache, point, this::createExtensionInstances);
        }

        private Object getOrCreate(Map<Class<?>, Object> map, Class<?> key, java.util.function.Function<Class<?>, ?> factory) {
            Object existing = map.get(key);
            if (existing != null) {
                if (existing instanceof Pending p) {
                    if (instantiationStack.get().contains(key)) {
                        throw new ServiceInstantiationException("Cyclic dependency while creating: " + key.getName(), null);
                    }
                    return p.future.join();
                }
                return existing;
            }

            Pending pending = new Pending();
            Object raced = map.putIfAbsent(key, pending);
            if (raced != null) {
                if (raced instanceof Pending p) {
                    if (instantiationStack.get().contains(key)) {
                        throw new ServiceInstantiationException("Cyclic dependency while creating: " + key.getName(), null);
                    }
                    return p.future.join();
                }
                return raced;
            }

            Deque<Class<?>> stack = instantiationStack.get();
            stack.push(key);
            try {
                Object created = factory.apply(key);
                pending.future.complete(created);
                map.put(key, created);
                return created;
            } catch (Throwable t) {
                pending.future.completeExceptionally(t);
                map.remove(key, pending);
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new ServiceInstantiationException("Failed to create: " + key.getName(), t);
            } finally {
                stack.pop();
            }
        }

        // --- Creation Logic ---

        private Object createServiceInstance(Class<?> key) {
            Class<?> actualClass = serviceBindings.getOrDefault(key, key);
            return instantiate(actualClass);
        }

        private List<Object> createExtensionInstances(Class<?> point) {
            List<Class<?>> impls = extensionDefinitions.get(point);
            if (impls == null || impls.isEmpty()) {
                return Collections.emptyList();
            }

            // Instantiate all implementations registered for this point
            List<Object> instances = impls.stream()
                    .map(this::instantiate)
                    .collect(Collectors.toList());

            return Collections.unmodifiableList(instances);
        }

        private Object instantiate(Class<?> clazz) {
            try {
                Constructor<?> constructor = clazz.getConstructor(Project.class);
                return constructor.newInstance(project);
            } catch (NoSuchMethodException e) {
                throw new ServiceInstantiationException(
                        "Class " + clazz.getName() + " must have a public constructor(Project).", e);
            } catch (InvocationTargetException e) {
                throw new ServiceInstantiationException(
                        "Class " + clazz.getName() + " threw an exception during initialization.", e.getCause());
            } catch (Exception e) {
                throw new ServiceInstantiationException(
                        "Failed to instantiate " + clazz.getName(), e);
            }
        }

        // --- Cleanup ---

        void disposeAll() {
            for (Object service : services.values()) {
                disposeIfDisposable(service);
            }
            services.clear();
            serviceBindings.clear();

            for (Object v : extensionCache.values()) {
                if (v instanceof Pending) {
                    continue;
                }
                if (v instanceof List<?> extensionList) {
                    for (Object extension : extensionList) {
                        disposeIfDisposable(extension);
                    }
                }
            }
            extensionCache.clear();
            extensionDefinitions.clear();
        }

        private void disposeIfDisposable(Object obj) {
            if (obj instanceof Disposable) {
                try {
                    ((Disposable) obj).dispose();
                } catch (Exception e) {
                    System.err.println("Error disposing " + obj.getClass().getName());
                    e.printStackTrace();
                }
            }
        }
    }

    public static class ServiceInstantiationException extends RuntimeException {
        public ServiceInstantiationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}