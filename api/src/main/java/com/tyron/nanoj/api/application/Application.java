package com.tyron.nanoj.api.application;

import com.tyron.nanoj.api.service.ServiceAccessHolder;
import com.tyron.nanoj.api.service.ServiceHost;

import java.util.List;

/**
 * Application-level access to globally-scoped services.
 * <p>
 * Unlike IntelliJ (where application implementations are internal), NanoJ allows platform layers
 * to provide their own {@link Application} implementation.
 */
public interface Application extends ServiceHost {

    @Override
    default <T> T getService(Class<T> serviceClass) {
        return ServiceAccessHolder.get().getApplicationService(serviceClass);
    }

    @Override
    default <E> List<E> getExtensions(Class<E> extensionPoint) {
        return ServiceAccessHolder.get().getApplicationExtensions(extensionPoint);
    }
}
