package com.tyron.nanoj.core.service;

import java.util.List;

/**
 * A scoped service container.
 * <p>
 * Containers can be composed in a parent/child hierarchy to model scopes (application → project → module).
 * A child container may override bindings/instances from its parent.
 */
public interface ServiceContainer {

    /**
     * @return the context used for instantiation decisions.
     */
    ServiceContext getContext();

    <T> T getService(Class<T> serviceClass);

    <T, I extends T> void registerBinding(Class<T> interfaceClass, Class<I> implementationClass);

    <T, I extends T> void registerBindingIfAbsent(Class<T> interfaceClass, Class<I> implementationClass);

    <T> void registerInstance(Class<T> serviceClass, T instance);

    <E, I extends E> void registerExtension(Class<E> extensionPoint, Class<I> extensionImpl);

    <E> List<E> getExtensions(Class<E> extensionPoint);

    /**
     * Disposes all cached services and extensions in this container.
     */
    void disposeAll();
}
