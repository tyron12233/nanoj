package com.tyron.nanoj.core.project;

import com.tyron.nanoj.api.tasks.ProjectRegistry;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.core.vfs.VirtualFileManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProjectConfigLifecycleListenerTest {

    @Test
    public void loadsNanojYamlAndRegistersProject() throws Exception {
        Path root = Files.createTempDirectory("nanoj_project_root");
        Path cache = Files.createTempDirectory("nanoj_project_cache");

        Files.writeString(root.resolve("nanoj.yaml"), """
                id: demo
                properties:
                  foo: bar
                subprojects:
                  - id: core
                    path: core
                """, StandardCharsets.UTF_8);

        var rootFo = VirtualFileManager.getInstance().find(root.toFile());
        MockProject project = new MockProject(cache.toFile(), rootFo);

        ProjectConfigLifecycleListener l = new ProjectConfigLifecycleListener(project);
        l.projectOpened(project);

        Assertions.assertEquals("demo", project.getConfiguration().getProperty("nanoj.id"));
        Assertions.assertEquals("bar", project.getConfiguration().getProperty("nanoj.properties.foo"));
        Assertions.assertEquals("core", project.getConfiguration().getProperty("nanoj.subproject.core.path"));

        Assertions.assertSame(project, ProjectRegistry.getInstance().getProject("demo"));

        l.projectClosing(project);
        Assertions.assertNull(ProjectRegistry.getInstance().getProject("demo"));

        // cleanup
        Files.walk(root).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
        Files.walk(cache).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }
}
