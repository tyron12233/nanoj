package com.tyron.nanoj.testFramework;

import com.tyron.nanoj.api.application.Application;
import com.tyron.nanoj.api.application.ApplicationManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Test platform application implementation.
 */
public final class TestApplication implements Application {

    private static final TestApplication INSTANCE = new TestApplication();

    private TestApplication() {
    }

    @Override
    public File getCacheDirectory() {
        try {
            return Files.createTempDirectory("NanojTest").toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void install() {
        ApplicationManager.setApplication(INSTANCE);
    }
}
