package com.tyron.nanoj.core.service;

import com.tyron.nanoj.api.service.ServiceAccessHolder;

/**
 * Installed via {@link Class#forName(String)} from {@code :api} when needed.
 */
public final class ServiceAccessBootstrap {

    static {
        ServiceAccessHolder.set(new CoreServiceAccess());
    }

    private ServiceAccessBootstrap() {
    }
}
