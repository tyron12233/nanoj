package com.tyron.nanoj.api.service;

import java.util.List;

/**
 * A host that can provide scoped services and extensions.
 * <p>
 * Mirrors IntelliJ's {@code ComponentManager#getService(Class)} concept.
 */
public interface ServiceHost {

    <T> T getService(Class<T> serviceClass);

    <E> List<E> getExtensions(Class<E> extensionPoint);
}
