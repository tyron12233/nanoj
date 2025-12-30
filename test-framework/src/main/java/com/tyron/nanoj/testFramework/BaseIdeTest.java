package com.tyron.nanoj.testFramework;

import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.core.concurrent.TaskSchedulerImpl;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.service.ApplicationServiceManager;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.core.vfs.JarFileSystem;
import com.tyron.nanoj.core.vfs.JrtFileSystem;
import com.tyron.nanoj.testFramework.vfs.TestVirtualFileManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Base class for all IDE tests.
 * <p>
 * - Resets the VFS before every test.
 * - Creates a generic MockProject.
 * - Provides helper methods for creating files quickly.
 */
public abstract class BaseIdeTest {

    @TempDir
    public File temporaryFolder;

    protected MockProject project;
    protected TestVirtualFileManager testVfs;

    @BeforeEach
    public final void baseSetUp() throws Exception {
        TestLogging.configureOnce();
        TestApplication.install();
        installTestVirtualFileManager();

        File projectRoot = new File(temporaryFolder, "MyProject");
        if (!projectRoot.mkdirs()) {
            throw new IOException("Failed to create project root parent directories");
        }

        var cache = new File(temporaryFolder, "cache");
        if (!cache.exists() && !cache.mkdir()) {
            throw new IOException("Failed to create cache dir");
        }

        MockFileObject rootFo = new MockFileObject(projectRoot.getAbsolutePath(), "");
        project = new MockProject(cache, rootFo);

        registerProjectServices();

        beforeEach();

        IndexManager.getInstance(project).onProjectOpened();
    }

    @AfterEach
    public final void baseTearDown() {
        try {
            afterEach();
        } finally {
            if (project != null) {
                ProjectServiceManager.disposeProject(project);
                project.dispose();
            }
            ApplicationServiceManager.disposeApplication();
        }
    }

    protected void registerProjectServices() {
        ProjectServiceManager.registerBindingIfAbsent(project, TaskScheduler.class, TaskSchedulerImpl.class);
    }

    /**
     * Subclasses override for per-test setup.
     * Called after the base project/VFS is ready.
     */
    protected void beforeEach() throws Exception {
    }

    /**
     * Subclasses override for per-test teardown.
     * Called before the base project is disposed.
     */
    protected void afterEach() {
    }

    /**
     * Helper to create a file in the "Project" scope.
     * Use simple paths like "src/Main.java".
     */
    protected MockFileObject file(String path, String content) {
        return file(path, content.getBytes(StandardCharsets.UTF_8));
    }

    protected MockFileObject file(String path, byte[] content) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String fullPath = project.getRootDirectory().getPath() + "/" + path;
        MockFileObject fo = new MockFileObject(fullPath, content);
        testVfs.registerFile(fo);

        VirtualFileManager.getInstance().fireFileCreated(fo);
        return fo;
    }

    protected MockFileObject dir(String path) {
        String fullPath = project.getRootDirectory().getPath() + "/" + path;
        MockFileObject fo = new MockFileObject(fullPath, "");
        fo.setFolder(true);
        testVfs.registerFile(fo);
        return fo;
    }

    /**
     * Helper to create a Java file specifically in a source root.
     */
    protected MockFileObject java(String className, String content) {
        return file("src/main/java/" + className.replace('.', '/') + ".java", content);
    }

    /**
     * Configures the project with a standard Java structure.
     */
    protected void configureJavaProject() {
        MockFileObject srcRoot = new MockFileObject(project.getRootDirectory().getPath() + "/src/main/java", "");
        srcRoot.setFolder(true);
        project.addSourceRoot(srcRoot);
        testVfs.registerFile(srcRoot);
    }

    private void installTestVirtualFileManager() {
        testVfs = new TestVirtualFileManager();
        // Ensure standard schemes used across core/lang tests resolve under the test VFM.
        // 'file' is typically provided by per-test MockFileSystem registrations.
        testVfs.register(JarFileSystem.getInstance());
        testVfs.register(JrtFileSystem.getInstance());
        ApplicationServiceManager.registerInstance(VirtualFileManager.class, testVfs);
    }
}