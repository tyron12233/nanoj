package com.tyron.nanoj.core.test;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;

import java.io.File;
import java.util.*;

/**
 * A fully mutable Project implementation for unit and integration testing.
 * <p>
 * Usage:
 * <pre>
 *     MockProject project = new MockProject(tempDir);
 *     project.addSourceRoot(new MockFileObject("/src", ""));
 * </pre>
 */
public class MockProject implements Project {

    private final String name;
    private final FileObject rootDir;
    private final File cacheDir;

    private final List<FileObject> sourceRoots = new ArrayList<>();
    private final List<FileObject> resourceRoots = new ArrayList<>();
    private final List<FileObject> classPath = new ArrayList<>();
    private final List<FileObject> bootClassPath = new ArrayList<>();
    
    private FileObject buildDir;
    private boolean isOpen = true;
    
    private final MockConfiguration configuration = new MockConfiguration();

    /**
     * Creates a mock project with a temporary cache directory.
     */
    public MockProject(File cacheDir) {
        this(cacheDir, new MockFileObject("/project_root", ""));
    }

    public MockProject(File cacheDir, FileObject rootDir) {
        this.name = "MockProject";
        this.cacheDir = cacheDir;
        this.rootDir = rootDir;
        
        // Default build dir
        this.buildDir = new MockFileObject(rootDir.getPath() + "/build", "");
    }

    public MockProject addSourceRoot(FileObject folder) {
        this.sourceRoots.add(folder);
        return this;
    }

    public MockProject addLibrary(FileObject jar) {
        this.classPath.add(jar);
        return this;
    }

    public MockProject setBuildDirectory(FileObject dir) {
        this.buildDir = dir;
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public FileObject getRootDirectory() {
        return rootDir;
    }

    @Override
    public List<FileObject> getSourceRoots() {
        return Collections.unmodifiableList(sourceRoots);
    }

    @Override
    public List<FileObject> getResourceRoots() {
        return Collections.unmodifiableList(resourceRoots);
    }

    @Override
    public List<FileObject> getClassPath() {
        return Collections.unmodifiableList(classPath);
    }

    @Override
    public List<FileObject> getBootClassPath() {
        return Collections.unmodifiableList(bootClassPath);
    }

    @Override
    public FileObject getBuildDirectory() {
        return buildDir;
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
        return isOpen;
    }

    @Override
    public void dispose() {
        this.isOpen = false;
    }

    // --- Inner Configuration Class ---

    public static class MockConfiguration implements ProjectConfiguration {
        private final Map<String, String> properties = new HashMap<>();
        private int javaVersion = 8;

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
            return javaVersion;
        }
        
        public void setJavaVersion(int version) {
            this.javaVersion = version;
        }
    }
}