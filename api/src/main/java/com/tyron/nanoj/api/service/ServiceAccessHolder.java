package com.tyron.nanoj.api.service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the active {@link ServiceAccess} implementation.
 * <p>
 * This lives in {@code :api} so API types can expose convenience methods without depending on
 * {@code :core}. The core implementation installs itself via a small bootstrap class.
 */
public final class ServiceAccessHolder {

    private static final AtomicReference<ServiceAccess> ACCESS = new AtomicReference<>();

    private ServiceAccessHolder() {
    }

    /**
     * Installed by {@code :core}.
     */
    public static void set(ServiceAccess access) {
        if (access == null) throw new IllegalArgumentException("access == null");
        ACCESS.set(access);
    }

    public static ServiceAccess get() {
        ServiceAccess access = ACCESS.get();
        if (access != null) {
            return access;
        }

        // Lazy bootstrap to avoid class-loading order issues.
        try {
            Class.forName("com.tyron.nanoj.core.service.ServiceAccessBootstrap", true,
                    ServiceAccessHolder.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            // :core not on the classpath
        }

        access = ACCESS.get();
        if (access == null) {
            throw new IllegalStateException(
                    "No ServiceAccess installed. Ensure ':core' is on the classpath (it provides the service container implementation)."
            );
        }
        return access;
    }
}
