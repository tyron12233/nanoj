package com.tyron.nanoj.desktop;

import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.indexing.IndexingProgressSnapshot;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.completion.CompletionCore;
import com.tyron.nanoj.core.editor.EditorCore;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.project.ProjectLifecycle;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.vfs.VirtualFileSystem;
import com.tyron.nanoj.lang.java.JavaLanguageSupport;
import com.tyron.nanoj.lang.java.compiler.JavacFileManagerService;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.JavaFullClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;
import com.tyron.nanoj.lang.java.indexing.ShortClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaSuperTypeIndex;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import com.tyron.nanoj.desktop.diagnostics.NanojDiagnosticsParser;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

        // 1) Create (or reuse) a persistent project directory.
        // Persisting the project root + cache dir means MapDB index files survive restarts.
        Path rootPath = resolveDesktopProjectRoot();
        Path srcRootPath = rootPath.resolve("src/main/java");
        Files.createDirectories(srcRootPath);

        Path mainPath = srcRootPath.resolve("Main.java");
        if (!Files.exists(mainPath)) {
            Files.writeString(mainPath, defaultMainSource(), StandardCharsets.UTF_8);
        }

        // 2) Create Nanoj Project backed by disk VFS
        FileObject rootFo = VirtualFileSystem.getInstance().find(rootPath.toFile());
        FileObject srcRootFo = VirtualFileSystem.getInstance().find(srcRootPath.toFile());
        FileObject mainFo = VirtualFileSystem.getInstance().find(mainPath.toFile());

        Path cachePath = resolveDesktopCacheDir();
        Files.createDirectories(cachePath);
        File cacheDir = cachePath.toFile();

        FileObject buildFo = VirtualFileSystem.getInstance().find(rootPath.resolve("build").toFile());

        Project project = new DesktopProject(
                "DesktopTempProject",
                rootFo,
                cacheDir,
                java.util.List.of(srcRootFo),
                buildFo
        );

        // Shared indexes (optional): configure before IndexManager is instantiated.
        String sharedIndexPaths = System.getProperty(IndexManager.SHARED_INDEX_PATHS_KEY);
        if (sharedIndexPaths == null || sharedIndexPaths.isBlank()) {
            sharedIndexPaths = System.getenv("NANOJ_SHARED_INDEX_PATHS");
        }
        if (sharedIndexPaths != null && !sharedIndexPaths.isBlank()) {
            project.getConfiguration().setProperty(IndexManager.SHARED_INDEX_PATHS_KEY, sharedIndexPaths);
        }

        // 3) Register Nanoj services/extensions
        EditorCore.register(project);
        CompletionCore.register(project);
        ProjectServiceManager.registerExtension(project, LanguageSupport.class, JavaLanguageSupport.class);

        // Java compiler + indexing services
        ProjectServiceManager.registerBindingIfAbsent(project, JavacFileManagerService.class, JavacFileManagerService.class);

        IndexManager indexManager = IndexManager.getInstance(project);
        indexManager.register(new JavaBinaryStubIndexer(project));
        indexManager.register(new ShortClassNameIndex(project));
        indexManager.register(new JavaFullClassNameIndex(project));
        indexManager.register(new JavaPackageIndex(project));
        indexManager.register(new JavaSuperTypeIndex(project));

        // Index JDK runtime modules (jrt:/) so Java stdlib is available to index-based features.
        indexJrtAsync(indexManager);

        // Make unit-like workloads never trip dumb mode by default.
        project.getConfiguration().setProperty(IndexManager.DUMB_THRESHOLD_MS_KEY, "5000");

        // Signal project lifecycle (starts indexing listeners)
        ProjectLifecycle.fireProjectOpened(project);

        // Index the initial file once so class-name completion can see it.
        indexManager.updateFile(mainFo);

        // 4) Build UI
        JFrame frame = new JFrame("Nanoj Desktop - " + rootPath);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        RSyntaxTextArea editor = new RSyntaxTextArea(30, 100);
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        editor.setCodeFoldingEnabled(true);
        editor.setAntiAliasingEnabled(true);
        editor.setText(Files.readString(mainPath, StandardCharsets.UTF_8));

        // Error highlighting (errors/warnings/info) via RSyntaxTextArea parser API.
        editor.addParser(new NanojDiagnosticsParser(project, mainFo, editor));

        applyDarkEditorTheme(editor);

        // Debounced save + reindex.
        Timer saveTimer = new Timer(350, e -> {
            try {
                Files.writeString(mainPath, editor.getText(), StandardCharsets.UTF_8);
                indexManager.updateFile(mainFo);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        });
        saveTimer.setRepeats(false);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                saveTimer.restart();
            }

            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });

        // 5) Wire completion: Ctrl+Space triggers Nanoj completion popup
        new NanojCompletionPopup(project, mainFo, editor);

        frame.add(new RTextScrollPane(editor), BorderLayout.CENTER);

        JLabel status = new JLabel();
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);
        String sharedConfigured = project.getConfiguration().getProperty(IndexManager.SHARED_INDEX_PATHS_KEY, "");

        java.util.concurrent.atomic.AtomicReference<IndexingProgressSnapshot> lastSnapshot = new java.util.concurrent.atomic.AtomicReference<>(
            new IndexingProgressSnapshot(0, 0, 0L, null)
        );

        Runnable renderStatus = () -> {
            IndexingProgressSnapshot s = lastSnapshot.get();
            boolean dumb = dumbService.isDumb();

            String mode = dumb ? "DUMB" : "SMART";
            String current = s.getCurrentFilePath() != null ? s.getCurrentFilePath() : "-";
            String shared = (sharedConfigured != null && !sharedConfigured.isBlank()) ? sharedConfigured : "(none)";

            status.setText(
                "Mode: " + mode +
                    "   Indexing: queued=" + s.getQueuedFiles() +
                    " running=" + s.getRunningFiles() +
                    " done=" + s.getCompletedFiles() +
                    " current=" + current +
                    "   Shared: " + shared +
                    "   Project: " + rootPath +
                    "   Main: " + mainPath.getFileName() +
                    "   Started: " + Instant.now()
            );
        };

        // Update label on indexing progress.
        com.tyron.nanoj.api.indexing.IndexingProgressListener progressListener = snapshot -> {
            lastSnapshot.set(Objects.requireNonNull(snapshot, "snapshot"));
            SwingUtilities.invokeLater(renderStatus);
        };
        indexManager.addProgressListener(progressListener);

        // Initial render.
        SwingUtilities.invokeLater(renderStatus);
        frame.add(status, BorderLayout.SOUTH);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                try {
                    saveTimer.stop();
                    Files.writeString(mainPath, editor.getText(), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    ex.printStackTrace();
                } finally {
                    indexManager.removeProgressListener(progressListener);
                    ProjectServiceManager.disposeProject(project);
                    project.dispose();
                }
            }
        });

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void installDarkLookAndFeel() {
        try {
            // Modern dark mode UI for Swing components.
            UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
        } catch (Throwable ignored) {
            // Best-effort; fall back to platform LAF.
        }
    }

    private static void applyDarkEditorTheme(RSyntaxTextArea editor) {
        try {
            Theme theme;
            try (var in = DesktopApp.class.getResourceAsStream(
                    "/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
                if (in == null) {
                    return;
                }
                theme = Theme.load(in);
            }
            theme.apply(editor);
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

    private static void indexJrtAsync(IndexManager indexManager) {
        Objects.requireNonNull(indexManager, "indexManager");

            Thread t = new Thread(() -> {
            try {
                FileObject modulesRoot;
                try {
                    modulesRoot = VirtualFileSystem.getInstance().find(URI.create("jrt:/modules"));
                } catch (Throwable ignored) {
                    // jrt may be unavailable (JDK8, stripped runtime, etc.)
                    return;
                }

                if (modulesRoot == null || !modulesRoot.exists() || !modulesRoot.isFolder()) {
                    return;
                }

                // IndexManager now understands folder/jrt/jar roots and will traverse+batch internally.
                indexManager.updateFileAsync(modulesRoot);
            } catch (Throwable ignored) {
            }
        }, "JRT-Indexer");
        t.setDaemon(true);
        t.start();
    }
}
