package com.tyron.nanoj.api.application;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Global holder for the active {@link Application} instance.
 * <p>
 * IntelliJ uses {@code ApplicationManager.getApplication()}.
 * NanoJ mirrors that API so platform layers can install their own application implementation.
 */
public final class ApplicationManager {

    private static final AtomicReference<Application> APPLICATION = new AtomicReference<>();

    private ApplicationManager() {
    }

    /**
     * Called by the platform layer (e.g. desktop/android) during startup.
     */
    public static void setApplication(Application application) {
        if (application == null) throw new IllegalArgumentException("application == null");
        APPLICATION.set(application);
    }

    public static Application getApplication() {
        Application app = APPLICATION.get();
        if (app == null) {
            throw new IllegalStateException("Application is not set. Call ApplicationManager.setApplication(...) during startup.");
        }
        return app;
    }
}
