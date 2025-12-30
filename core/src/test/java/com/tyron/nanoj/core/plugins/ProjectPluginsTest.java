package com.tyron.nanoj.core.plugins;

import com.tyron.nanoj.api.plugins.ProjectPlugin;
import com.tyron.nanoj.api.plugins.ProjectPluginRegistry;
import com.tyron.nanoj.api.tasks.TasksService;
import com.tyron.nanoj.core.project.ProjectConfigLifecycleListener;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.tasks.TasksServiceImpl;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProjectPluginsTest {

    @Test
    public void loadsPluginsFromYamlAndAppliesInOrder() throws Exception {
        Path root = Files.createTempDirectory("nanoj_plugins_root");
        Path cache = Files.createTempDirectory("nanoj_plugins_cache");

        Files.writeString(root.resolve("nanoj.yaml"), """
                id: demo
                plugins:
                  - id: p1
                    options:
                      value: one
                  - p2
                """, StandardCharsets.UTF_8);

        var rootFo = VirtualFileManager.getInstance().find(root.toFile());
        MockProject project = new MockProject(cache.toFile(), rootFo);

        // Ensure TasksService.getInstance(project) works inside plugin apply.
        TasksServiceImpl tasks = new TasksServiceImpl(project);
        ProjectServiceManager.registerInstance(project, TasksService.class, tasks);

        ProjectPluginRegistry registry = ProjectPluginRegistry.getInstance();
        try {
            // p1 writes "1" then p2 appends "2"
            registry.register("p1", p -> new ProjectPlugin() {
            @Override
            public String getId() {
                return "p1";
            }

            @Override
            public void apply(com.tyron.nanoj.api.plugins.ProjectPluginContext context) {
                context.getProject().getConfiguration().setProperty("test.order", "1");
                String v = context.getOptions().get("value", "");
                context.getProject().getConfiguration().setProperty("test.p1.value", v);
            }
            });

            registry.register("p2", p -> new ProjectPlugin() {
                @Override
                public String getId() {
                    return "p2";
                }

                @Override
                public void apply(com.tyron.nanoj.api.plugins.ProjectPluginContext context) {
                    String order = context.getProject().getConfiguration().getProperty("test.order", "");
                    context.getProject().getConfiguration().setProperty("test.order", order + "2");
                }
            });

            ProjectConfigLifecycleListener l = new ProjectConfigLifecycleListener(project);
            l.projectOpened(project);

            Assertions.assertEquals("one", project.getConfiguration().getProperty("test.p1.value"));
            Assertions.assertEquals("12", project.getConfiguration().getProperty("test.order"));
            Assertions.assertEquals("true", project.getConfiguration().getProperty("nanoj.plugin.p1.applied"));
            Assertions.assertEquals("true", project.getConfiguration().getProperty("nanoj.plugin.p2.applied"));

            // Cleanup
            l.projectClosing(project);
        } finally {
            registry.unregister("p1");
            registry.unregister("p2");

            tasks.dispose();
            ProjectServiceManager.disposeProject(project);
            project.dispose();

            Files.walk(root).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
            Files.walk(cache).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }
}
