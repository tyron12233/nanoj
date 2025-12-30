package com.tyron.nanoj.lang.java.plugins;

import com.tyron.nanoj.api.plugins.ProjectPluginRegistry;
import com.tyron.nanoj.api.service.ServiceAccessHolder;
import com.tyron.nanoj.api.tasks.Task;
import com.tyron.nanoj.api.tasks.TaskResult;
import com.tyron.nanoj.api.tasks.TasksService;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.project.ProjectConfigLifecycleListener;
import com.tyron.nanoj.core.service.ApplicationServiceManager;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.tasks.TasksServiceImpl;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.core.vfs.VirtualFileManagerImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

public class JavaPluginTest {

    @BeforeEach
    public void setup() {
        ApplicationServiceManager.registerBinding(VirtualFileManager.class, VirtualFileManagerImpl.class);
    }

    @Test
    public void javaPluginCompilesResourcesAndBuildsJar() throws Exception {
        Assumptions.assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "JDK compiler required");

        Path root = Files.createTempDirectory("nanoj_java_plugin_root");
        Path cache = Files.createTempDirectory("nanoj_java_plugin_cache");

        Path srcRoot = root.resolve("src/main/java");
        Path resRoot = root.resolve("src/main/resources");
        Files.createDirectories(srcRoot);
        Files.createDirectories(resRoot);

        Files.writeString(resRoot.resolve("hello.txt"), "hi", StandardCharsets.UTF_8);

        Files.writeString(srcRoot.resolve("Main.java"), """
                public class Main {
                  public static void main(String[] args) {
                    System.out.println(\"OK\");
                  }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(root.resolve("nanoj.yaml"), """
                id: demo
                plugins:
                  - id: java
                    options:
                      mainClass: Main
                      jarName: demo.jar
                """, StandardCharsets.UTF_8);

        var rootFo = VirtualFileManager.getInstance().find(root.toFile());
        var buildFo = VirtualFileManager.getInstance().find(root.resolve("build").toFile());

        // MockProject(rootFo) gives us correct source/resource roots.
        MockProject project = new MockProject(cache.toFile(), rootFo) {
            @Override
            public List<FileObject> getSourceRoots() {
                return List.of(VirtualFileManager.getInstance().find(srcRoot.toFile()));
            }

            @Override
            public List<FileObject> getResourceRoots() {
                return List.of(VirtualFileManager.getInstance().find(resRoot.toFile()));
            }

            @Override
            public FileObject getBuildDirectory() {
                return buildFo;
            }

            @Override
            public List<FileObject> getClassPath() {
                return List.of();
            }

            @Override
            public List<FileObject> getBootClassPath() {
                return List.of();
            }

            @Override
            public String getName() {
                return "demo";
            }
        };

        TasksServiceImpl tasksImpl = new TasksServiceImpl(project);
        ProjectServiceManager.registerInstance(project, TasksService.class, tasksImpl);

        ProjectPluginRegistry.getInstance().register(JavaPlugin.ID, p -> new JavaPlugin());

        try {
            ProjectConfigLifecycleListener l = new ProjectConfigLifecycleListener(project);
            l.projectOpened(project);

            TasksService tasks = TasksService.getInstance(project);
            Task jar = tasks.getTask(JavaPlugin.TASK_JAR);
            Assertions.assertNotNull(jar);

            var run = tasks.run(jar);
            Assertions.assertEquals(TaskResult.Status.EXECUTED, run.getResult(jar).getStatus());

            File jarOnDisk = new File(buildFo.getChild("libs").getChild("demo.jar").getPath());
            Assertions.assertTrue(jarOnDisk.exists());

            try (JarFile jf = new JarFile(jarOnDisk)) {
                Assertions.assertNotNull(jf.getEntry("Main.class"));
                Assertions.assertNotNull(jf.getEntry("hello.txt"));
            }

            l.projectClosing(project);
        } finally {
            try { ProjectPluginRegistry.getInstance().unregister(JavaPlugin.ID); } catch (Throwable ignored) {}
            tasksImpl.dispose();
            ProjectServiceManager.disposeProject(project);
            project.dispose();

            deleteRecursive(root);
            deleteRecursive(cache);
        }
    }

    private static void deleteRecursive(Path p) {
        try {
            if (!Files.exists(p)) return;
            Files.walk(p)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}
