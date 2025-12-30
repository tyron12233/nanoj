package com.tyron.nanoj.desktop;

import com.tyron.nanoj.api.application.Application;
import com.tyron.nanoj.api.application.ApplicationManager;

/**
 * Desktop platform application implementation.
 * <p>
 * This is the hook where desktop can register/override application-scoped services.
 */
public final class DesktopApplication implements Application {

    private static final DesktopApplication INSTANCE = new DesktopApplication();

    private DesktopApplication() {
    }

    public static DesktopApplication getInstance() {
        return INSTANCE;
    }

    /**
     * Installs the desktop {@link Application} into {@link ApplicationManager}.
     * Safe to call multiple times.
     */
    public static void install() {
        ApplicationManager.setApplication(INSTANCE);
    }
}
