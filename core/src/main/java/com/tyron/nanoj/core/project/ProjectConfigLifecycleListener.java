package com.tyron.nanoj.core.project;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.project.ProjectLifecycleListener;
import com.tyron.nanoj.api.plugins.ProjectPlugin;
import com.tyron.nanoj.api.plugins.ProjectPluginContext;
import com.tyron.nanoj.api.plugins.ProjectPluginOptions;
import com.tyron.nanoj.api.plugins.ProjectPluginRegistry;
import com.tyron.nanoj.api.tasks.ProjectRegistry;
import com.tyron.nanoj.api.tasks.TasksService;
import com.tyron.nanoj.api.vfs.FileObject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads NanoJ project configuration from the project root (nanoj.yaml / nanoj.yml)
 * and registers the project in the application {@link ProjectRegistry}.
 */
public final class ProjectConfigLifecycleListener implements ProjectLifecycleListener {

    private final Project project;
    private volatile String registeredId;

    public ProjectConfigLifecycleListener(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    @Override
    public void projectOpened(Project project) {
        loadConfigIntoProjectConfiguration();

        applyConfiguredPlugins();

        String id = this.project.getConfiguration().getProperty("nanoj.id", this.project.getName());
        this.registeredId = id;

        try {
            ProjectRegistry.getInstance().register(id, this.project);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    @Override
    public void projectClosing(Project project) {
        String id = registeredId;
        if (id == null || id.isBlank()) {
            id = this.project.getConfiguration().getProperty("nanoj.id", this.project.getName());
        }

        try {
            ProjectRegistry.getInstance().unregister(id);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private void loadConfigIntoProjectConfiguration() {
        FileObject root = this.project.getRootDirectory();
        if (root == null) return;

        FileObject config = root.getChild("nanoj.yaml");
        if (config == null || !config.exists()) {
            config = root.getChild("nanoj.yml");
        }
        if (config == null || !config.exists()) return;

        try (InputStream in = config.getInputStream()) {
            Object doc = new Yaml().load(in);
            if (!(doc instanceof Map<?, ?> map)) return;

            // id: string
            Object id = map.get("id");
            if (id != null) {
                this.project.getConfiguration().setProperty("nanoj.id", String.valueOf(id));
            }

            // properties: {k: v}
            Object props = map.get("properties");
            if (props instanceof Map<?, ?> propsMap) {
                for (Map.Entry<?, ?> e : propsMap.entrySet()) {
                    if (e.getKey() == null) continue;
                    String key = "nanoj.properties." + e.getKey();
                    String value = (e.getValue() != null) ? String.valueOf(e.getValue()) : "";
                    this.project.getConfiguration().setProperty(key, value);
                }
            }

            // subprojects: [{id, path}, ...]
            Object subs = map.get("subprojects");
            if (subs instanceof List<?> list) {
                StringBuilder ids = new StringBuilder();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> sp)) continue;
                    Object spId = sp.get("id");
                    Object spPath = sp.get("path");
                    if (spId == null) continue;

                    String sid = String.valueOf(spId);
                    if (!ids.isEmpty()) ids.append(',');
                    ids.append(sid);

                    if (spPath != null) {
                        this.project.getConfiguration().setProperty("nanoj.subproject." + sid + ".path", String.valueOf(spPath));
                    }
                }
                if (!ids.isEmpty()) {
                    this.project.getConfiguration().setProperty("nanoj.subprojects", ids.toString());
                }
            }

            // plugins: ["java-library", {id: "application", options: {...}}, ...]
            Object plugins = map.get("plugins");
            if (plugins instanceof List<?> list) {
                StringBuilder ids = new StringBuilder();
                for (Object item : list) {
                    String pluginId = null;
                    Map<?, ?> options = null;

                    if (item instanceof String s) {
                        pluginId = s;
                    } else if (item instanceof Map<?, ?> pm) {
                        Object idObj = pm.get("id");
                        if (idObj != null) {
                            pluginId = String.valueOf(idObj);
                        }
                        Object optsObj = pm.get("options");
                        if (optsObj instanceof Map<?, ?> om) {
                            options = om;
                        }
                    }

                    if (pluginId == null || pluginId.isBlank()) continue;
                    String pid = pluginId.trim();
                    if (!ids.isEmpty()) ids.append(',');
                    ids.append(pid);

                    if (options != null && !options.isEmpty()) {
                        for (Map.Entry<?, ?> e : options.entrySet()) {
                            if (e.getKey() == null) continue;
                            String k = String.valueOf(e.getKey());
                            String v = (e.getValue() != null) ? String.valueOf(e.getValue()) : "";
                            this.project.getConfiguration().setProperty(
                                    ProjectPluginOptions.PREFIX + pid + ProjectPluginOptions.OPTIONS_SEGMENT + k,
                                    v
                            );
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    this.project.getConfiguration().setProperty("nanoj.plugins", ids.toString());
                }
            }
        } catch (Throwable ignored) {
            // Parsing/config is optional.
        }
    }

    private void applyConfiguredPlugins() {
        String raw = this.project.getConfiguration().getProperty("nanoj.plugins", "");
        if (raw == null || raw.isBlank()) return;

        String[] ids = raw.split(",");
        ProjectPluginRegistry registry;
        try {
            registry = ProjectPluginRegistry.getInstance();
        } catch (Throwable t) {
            return;
        }

        for (String id : ids) {
            if (id == null) continue;
            String pluginId = id.trim();
            if (pluginId.isEmpty()) continue;

            try {
                ProjectPlugin plugin = registry.create(this.project, pluginId);
                if (plugin == null) continue;

                ProjectPluginContext ctx = new ProjectPluginContext() {
                    @Override
                    public Project getProject() {
                        return ProjectConfigLifecycleListener.this.project;
                    }

                    @Override
                    public TasksService getTasks() {
                        return TasksService.getInstance(ProjectConfigLifecycleListener.this.project);
                    }

                    @Override
                    public ProjectPluginOptions getOptions() {
                        return new ProjectPluginOptions(ProjectConfigLifecycleListener.this.project.getConfiguration(), pluginId);
                    }
                };

                plugin.apply(ctx);

                // Mark applied (handy for debugging/tests).
                this.project.getConfiguration().setProperty("nanoj.plugin." + pluginId + ".applied", "true");
            } catch (Throwable ignored) {
                // Best-effort: a bad plugin should not prevent project opening.
            }
        }
    }
}
