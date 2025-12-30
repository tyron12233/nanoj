package com.tyron.nanoj.testFramework;

import com.tyron.nanoj.api.application.Application;
import com.tyron.nanoj.api.application.ApplicationManager;

/**
 * Test platform application implementation.
 */
public final class TestApplication implements Application {

    private static final TestApplication INSTANCE = new TestApplication();

    private TestApplication() {
    }

    public static void install() {
        ApplicationManager.setApplication(INSTANCE);
    }
}
