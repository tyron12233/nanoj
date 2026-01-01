package com.tyron.nanoj.desktop;

import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.editor.EditorManager;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.plugins.ProjectPluginRegistry;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.completion.CompletionCore;
import com.tyron.nanoj.core.dumb.DumbServiceImpl;
import com.tyron.nanoj.core.editor.EditorCore;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.core.indexing.IndexManagerImpl;
import com.tyron.nanoj.core.project.ProjectLifecycle;
import com.tyron.nanoj.core.service.ApplicationServiceManager;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.desktop.ui.DesktopEditorManager;
import com.tyron.nanoj.lang.java.JavaLanguageSupport;
import com.tyron.nanoj.lang.java.plugins.JavaPlugin;
import com.tyron.nanoj.lang.java.compiler.JavacFileManagerService;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.JavaFullClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;
import com.tyron.nanoj.lang.java.indexing.ShortClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaSuperTypeIndex;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class DesktopApp {

    public static void main(String[] args) {
        String display = System.getenv("DISPLAY");
        String wayland = System.getenv("WAYLAND_DISPLAY");
        if ((display == null || display.isBlank()) && (wayland == null || wayland.isBlank())) {
            System.err.println("Nanoj Desktop: no DISPLAY/WAYLAND_DISPLAY; skipping UI startup.");
            System.err.println("Run this on a machine with a graphical session.");
            return;
        }

        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    new DesktopApp().start();
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            });
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Nanoj Desktop: failed to load AWT native libraries; skipping UI startup.");
            e.printStackTrace();
        }
    }

    private void start() throws IOException {
        installDarkLookAndFeel();

        DesktopApplication.install();

        // Register built-in plugins available on Desktop.
        ProjectPluginRegistry.getInstance().register(JavaPlugin.ID, p -> new JavaPlugin());

        Path rootPath = resolveDesktopProjectRoot();
        Path srcRootPath = rootPath.resolve("src/main/java");
        Path resourcesRootPath = rootPath.resolve("src/main/resources");
        Files.createDirectories(srcRootPath);
        Files.createDirectories(resourcesRootPath);

        Path mainPath = srcRootPath.resolve("Main.java");
        if (!Files.exists(mainPath)) {
            Files.writeString(mainPath, defaultMainSource(), StandardCharsets.UTF_8);
        }

        Path configPath = rootPath.resolve("nanoj.yaml");
        if (!Files.exists(configPath)) {
            Files.writeString(configPath,
                    "plugins:\n" +
                            "  - id: java\n" +
                            "    options:\n" +
                            "      mainClass: Main\n",
                    StandardCharsets.UTF_8);
        }

        FileObject rootFo = VirtualFileManager.getInstance().find(rootPath.toFile());
        FileObject srcRootFo = VirtualFileManager.getInstance().find(srcRootPath.toFile());
        FileObject resourcesRootFo = VirtualFileManager.getInstance().find(resourcesRootPath.toFile());
        FileObject mainFo = VirtualFileManager.getInstance().find(mainPath.toFile());

        Path cachePath = resolveDesktopCacheDir();
        Files.createDirectories(cachePath);
        File cacheDir = cachePath.toFile();

        FileObject buildFo = VirtualFileManager.getInstance().find(rootPath.resolve("build").toFile());

        Project project = new DesktopProject(
                "DesktopTempProject",
                rootFo,
                cacheDir,
                java.util.List.of(srcRootFo),
            java.util.List.of(resourcesRootFo),
                buildFo
        );

        ApplicationServiceManager.registerBinding(
                IndexManager.class,
                IndexManagerImpl.class
        );
        ApplicationServiceManager.registerBinding(
                DumbService.class,
                DumbServiceImpl.class
        );

        EditorCore.register(project);
        CompletionCore.register(project);
        ProjectServiceManager.registerExtension(project, LanguageSupport.class, JavaLanguageSupport.class);

        DesktopEditorManager desktopEditorManager = new DesktopEditorManager(project);
        ProjectServiceManager.registerInstance(project, EditorManager.class, desktopEditorManager);

        // Java compiler + indexing services
        ProjectServiceManager.registerBindingIfAbsent(project, JavacFileManagerService.class, JavacFileManagerService.class);

        IndexManager indexManager = IndexManager.getInstance();
        indexManager.register(new JavaBinaryStubIndexer(project));
        indexManager.register(new ShortClassNameIndex(project));
        indexManager.register(new JavaFullClassNameIndex(project));
        indexManager.register(new JavaPackageIndex(project));
        indexManager.register(new JavaSuperTypeIndex(project));

        ProjectLifecycle.fireProjectOpened(project);

        applyDarkEditorThemeToRsyntax();

        desktopEditorManager.getFrame().show();
        desktopEditorManager.getFrame().openInitialFile(mainFo);

        desktopEditorManager.getFrame().getSwingFrame().addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                try {
                    ProjectLifecycle.fireProjectClosing(project);
                } finally {
                    ProjectServiceManager.disposeProject(project);
                    project.dispose();
                }
            }
        });
    }

    private static void installDarkLookAndFeel() {
        try {
            // modern dark mode UI for Swing components.
            UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
        } catch (Throwable ignored) {
            // best-effort; fall back to platform LAF.
        }
    }

    private static void applyDarkEditorThemeToRsyntax() {
        // best-effort: simply ensure RSyntaxTextArea has its theme resource available.
        // individual editor tabs load their own RSyntaxTextArea instances.
        try (var ignored = DesktopApp.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
            // no-op
        } catch (Throwable ignored) {
        }
    }

    private static Path resolveDesktopProjectRoot() throws IOException {
        String override = System.getProperty("nanoj.desktop.projectDir");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override.trim());
            Files.createDirectories(p);
            return p;
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path base;
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            base = Path.of(xdgDataHome.trim());
        } else {
            String home = System.getProperty("user.home", ".");
            base = Path.of(home).resolve(".local/share");
        }

        Path root = base.resolve("nanoj").resolve("desktop-project");
        Files.createDirectories(root);
        return root;
    }

    private static Path resolveDesktopCacheDir() throws IOException {
        String override = System.getProperty("nanoj.desktop.cacheDir");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override.trim());
            Files.createDirectories(p);
            return p;
        }

        String xdgCacheHome = System.getenv("XDG_CACHE_HOME");
        Path base;
        if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
            base = Path.of(xdgCacheHome.trim());
        } else {
            String home = System.getProperty("user.home", ".");
            base = Path.of(home).resolve(".cache");
        }

        Path cache = base.resolve("nanoj").resolve("desktop-project").resolve(".nanoj-cache");
        Files.createDirectories(cache);
        return cache;
    }

    private static String defaultMainSource() {
        return "public class Main {\n" +
                "    public static void main(String[] args) {\n" +
                "        String s = \"Hello\";\n" +
                "        s.\n" +
                "    }\n" +
                "}\n";
    }
}
