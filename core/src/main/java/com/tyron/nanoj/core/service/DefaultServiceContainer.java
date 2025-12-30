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
 * Default {@link ServiceContainer} implementation.
 * <p>
 * - Thread-safe service creation with cycle detection
 * - Lazy extension instantiation
 * - Optional parent container for hierarchical scopes
 */
public class DefaultServiceContainer implements ServiceContainer {

    private final ServiceContext context;

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

    public DefaultServiceContainer(ServiceContext context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.context = context;
    }

    @Override
    public ServiceContext getContext() {
        return context;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getService(Class<T> key) {
        // Services belong strictly to this scope.
        return (T) getOrCreate(services, key, this::createServiceInstance);
    }

    @Override
    public <T, I extends T> void registerBinding(Class<T> interfaceClass, Class<I> implClass) {
        if (services.containsKey(interfaceClass)) {
            throw new IllegalStateException("Cannot bind " + interfaceClass.getName() + " because it is already instantiated.");
        }
        serviceBindings.put(interfaceClass, implClass);
    }

    @Override
    public <T, I extends T> void registerBindingIfAbsent(Class<T> interfaceClass, Class<I> implClass) {
        if (services.containsKey(interfaceClass)) {
            return;
        }
        serviceBindings.putIfAbsent(interfaceClass, implClass);
    }

    @Override
    public <T> void registerInstance(Class<T> serviceClass, T instance) {
        services.put(serviceClass, instance);
    }

    @Override
    public <E, I extends E> void registerExtension(Class<E> point, Class<I> impl) {
        // Invalidate cache if new extensions are added at runtime.
        extensionCache.remove(point);

        extensionDefinitions.computeIfAbsent(point, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(impl);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> getExtensions(Class<E> point) {
        Object cached = extensionCache.get(point);
        if (cached instanceof List<?> list) {
            return (List<E>) list;
        }

        return (List<E>) getOrCreate(extensionCache, point, this::createLocalExtensionInstances);
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

    private List<Object> createLocalExtensionInstances(Class<?> point) {
        List<Class<?>> impls = extensionDefinitions.get(point);
        if (impls == null || impls.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object> instances = impls.stream()
                .map(this::instantiate)
                .collect(Collectors.toList());

        return Collections.unmodifiableList(instances);
    }

    private Object instantiate(Class<?> clazz) {
        // Prefer Project-aware services when we have a project context.
        Project project = context.getProjectOrNull();
        if (project != null) {
            try {
                Constructor<?> constructor = clazz.getConstructor(Project.class);
                return constructor.newInstance(project);
            } catch (NoSuchMethodException ignored) {
                // fall back to no-arg
            } catch (InvocationTargetException e) {
                throw new ServiceInstantiationException(
                        "Class " + clazz.getName() + " threw an exception during initialization.", e.getCause());
            } catch (Exception e) {
                throw new ServiceInstantiationException(
                        "Failed to instantiate " + clazz.getName(), e);
            }
        }

        // Application scope (or project scope services that use no-arg constructors)
        try {
            Constructor<?> ctor = clazz.getConstructor();
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            if (project != null) {
                throw new ServiceInstantiationException(
                        "Class " + clazz.getName() + " must have a public constructor(Project) or a public no-arg constructor.", e);
            }
            throw new ServiceInstantiationException(
                    "Class " + clazz.getName() + " must have a public no-arg constructor for application scope.", e);
        } catch (InvocationTargetException e) {
            throw new ServiceInstantiationException(
                    "Class " + clazz.getName() + " threw an exception during initialization.", e.getCause());
        } catch (Exception e) {
            throw new ServiceInstantiationException(
                    "Failed to instantiate " + clazz.getName(), e);
        }
    }

    // --- Cleanup ---

    @Override
    public void disposeAll() {
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
