package com.tyron.nanoj.testFramework;

import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.core.test.MockFileSystem;

/**
 * A fluent builder to setup complex project scenarios.
 */
public class TestProjectBuilder {

    private final MockProject project;
    private final MockFileSystem fs;
    
    public TestProjectBuilder(MockProject project, MockFileSystem fs) {
        this.project = project;
        this.fs = fs;
    }

    public TestProjectBuilder withSourceRoot(String relativePath) {
        String fullPath = project.getRootDirectory().getPath() + "/" + relativePath;
        MockFileObject dir = new MockFileObject(fullPath, "");
        dir.setFolder(true);
        fs.registerFile(dir);
        project.addSourceRoot(dir);
        return this;
    }

    public TestProjectBuilder withLibrary(String path) {
        MockFileObject jar = new MockFileObject(path, new byte[0]);
        fs.registerFile(jar);
        project.addLibrary(jar);
        return this;
    }
    
    public TestProjectBuilder withProperty(String key, String value) {
        project.getConfiguration().setProperty(key, value);
        return this;
    }

    public MockProject build() {
        return project;
    }
}