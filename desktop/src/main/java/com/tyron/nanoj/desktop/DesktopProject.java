package com.tyron.nanoj.desktop;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class DesktopProject implements Project {

    private final String name;
    private final FileObject rootDirectory;
    private final File cacheDir;

    private final List<FileObject> sourceRoots;
    private final List<FileObject> resourceRoots;
    private final List<FileObject> classPath;
    private final List<FileObject> bootClassPath;

    private final FileObject buildDirectory;
    private final DesktopConfiguration configuration = new DesktopConfiguration();

    private volatile boolean open = true;

    DesktopProject(
            String name,
            FileObject rootDirectory,
            File cacheDir,
            List<FileObject> sourceRoots,
            FileObject buildDirectory
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");

        this.sourceRoots = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(sourceRoots, "sourceRoots")));
        this.resourceRoots = List.of();
        this.classPath = List.of();
        this.bootClassPath = List.of();

        this.buildDirectory = Objects.requireNonNull(buildDirectory, "buildDirectory");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public FileObject getRootDirectory() {
        return rootDirectory;
    }

    @Override
    public List<FileObject> getSourceRoots() {
        return sourceRoots;
    }

    @Override
    public List<FileObject> getResourceRoots() {
        return resourceRoots;
    }

    @Override
    public List<FileObject> getClassPath() {
        return classPath;
    }

    @Override
    public List<FileObject> getBootClassPath() {
        return bootClassPath;
    }

    @Override
    public FileObject getBuildDirectory() {
        return buildDirectory;
    }

    @Override
    public File getCacheDir() {
        return cacheDir;
    }

    @Override
    public ProjectConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void dispose() {
        open = false;
    }

    private static final class DesktopConfiguration implements ProjectConfiguration {
        private final java.util.Map<String, String> properties = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String getProperty(String key) {
            return properties.get(key);
        }

        @Override
        public String getProperty(String key, String defaultValue) {
            return properties.getOrDefault(key, defaultValue);
        }

        @Override
        public void setProperty(String key, String value) {
            properties.put(key, value);
        }

        @Override
        public int getJavaVersion() {
            return 8;
        }
    }
}
