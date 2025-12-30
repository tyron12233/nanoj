package com.tyron.nanoj.core.service;

/**
 * Thrown when a service or extension cannot be instantiated by a {@link ServiceContainer}.
 */
public class ServiceInstantiationException extends RuntimeException {

    public ServiceInstantiationException(String message, Throwable cause) {
        super(message, cause);
    }
}
