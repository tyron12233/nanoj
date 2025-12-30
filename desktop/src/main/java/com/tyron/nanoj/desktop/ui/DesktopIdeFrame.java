package com.tyron.nanoj.desktop.ui;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.indexing.IndexingProgressListener;
import com.tyron.nanoj.api.indexing.IndexingProgressSnapshot;
import com.tyron.nanoj.api.tasks.Task;
import com.tyron.nanoj.api.tasks.TaskExecutionListener;
import com.tyron.nanoj.api.tasks.TaskResult;
import com.tyron.nanoj.api.tasks.TasksService;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.vfs.VirtualFileSystem;
import com.tyron.nanoj.desktop.NanojCompletionPopup;
import com.tyron.nanoj.desktop.diagnostics.NanojDiagnosticsParser;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Desktop IDE frame: file tree (left), editor tabs (center), console (bottom) and Run button.
 */
public final class DesktopIdeFrame {

    private final Project project;
    private final IndexManager indexManager;
    private final DesktopTabs tabs;

    private final JFrame frame;
    private final JTree fileTree;
    private final JTabbedPane editorTabs;
    private final JTextArea console;
    private final JButton runButton;

    private final JLabel statusLabel;
    private final JProgressBar statusProgress;

    private final DefaultTreeModel treeModel;

    private final TaskExecutionListener taskListener;

    private final IndexingProgressListener indexingListener;

    public DesktopIdeFrame(Project project) {
        this.project = Objects.requireNonNull(project, "project");
        this.indexManager = IndexManager.getInstance(project);

        this.frame = new JFrame("Nanoj - " + project.getName());
        this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.frame.setLayout(new BorderLayout());

        this.editorTabs = new JTabbedPane();
        this.console = new JTextArea();
        this.console.setEditable(false);
        this.console.setFont(resolveEditorFont(project, 12));

        this.runButton = new JButton("Run");

        this.statusLabel = new JLabel(" ");
        this.statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        this.statusLabel.setFont(resolveEditorFont(project, 12));

        this.statusProgress = new JProgressBar();
        this.statusProgress.setIndeterminate(false);
        this.statusProgress.setVisible(false);

        this.tabs = new DesktopTabs(project, indexManager, editorTabs);

        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(new File(project.getRootDirectory().getPath()));
        this.treeModel = new DefaultTreeModel(rootNode);
        this.fileTree = new JTree(treeModel);
        this.fileTree.setRootVisible(true);
        this.fileTree.setShowsRootHandles(true);

        this.fileTree.setCellRenderer(new FileTreeRenderer(project));

        populateChildren(rootNode);

        this.taskListener = new TaskExecutionListener() {
            @Override
            public void onTaskStarted(Task task) {
                SwingUtilities.invokeLater(() -> appendConsole("[task] " + task.getId() + " RUNNING\n"));
            }

            @Override
            public void onTaskFinished(Task task, TaskResult result) {
                SwingUtilities.invokeLater(() -> {
                    appendConsole("[task] " + task.getId() + " " + result.getStatus() + "\n");
                    if (result.getError() != null) {
                        appendConsole(renderError(project, result.getError()) + "\n");
                    }
                });
            }
        };

        try {
            TasksService.getInstance(project).addListener(taskListener);
        } catch (Throwable ignored) {
        }

        this.indexingListener = snapshot -> SwingUtilities.invokeLater(() -> renderIndexingStatus(snapshot));
        try {
            indexManager.addProgressListener(indexingListener);
        } catch (Throwable ignored) {
        }

        // Best-effort cleanup.
        this.frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                try {
                    try {
                        TasksService.getInstance(DesktopIdeFrame.this.project).removeListener(taskListener);
                    } catch (Throwable ignored) {
                    }
                    try {
                        DesktopIdeFrame.this.indexManager.removeProgressListener(indexingListener);
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        wireUi();

        // Initial status render.
        renderIndexingStatus(indexManager.getProgressSnapshot());
    }

    public void show() {
        frame.setSize(1100, 750);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public JFrame getSwingFrame() {
        return frame;
    }

    public void openInitialFile(FileObject file) {
        try {
            tabs.openFile(file);
        } catch (IOException e) {
            appendConsole("Failed to open: " + file.getPath() + "\n" + e + "\n");
        }
    }

    private void wireUi() {
        // Left: file tree
        JScrollPane treeScroll = new JScrollPane(fileTree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());

        // Center: editor tabs
        JPanel editorPanel = new JPanel(new BorderLayout());
        editorPanel.add(editorTabs, BorderLayout.CENTER);

        // Bottom: console
        JScrollPane consoleScroll = new JScrollPane(console);
        consoleScroll.setBorder(BorderFactory.createEmptyBorder());

        // Horizontal split (tree | editor)
        JSplitPane horiz = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, editorPanel);
        horiz.setResizeWeight(0.20);

        // Vertical split (top | console)
        JSplitPane vert = new JSplitPane(JSplitPane.VERTICAL_SPLIT, horiz, consoleScroll);
        vert.setResizeWeight(0.75);

        // Toolbar
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(runButton);

        // IntelliJ-ish FlatLaf styling hints (best-effort).
        try {
            editorTabs.putClientProperty("JTabbedPane.tabType", "underlined");
            editorTabs.putClientProperty("JTabbedPane.tabAreaAlignment", "leading");
            toolbar.putClientProperty("JToolBar.isRollover", Boolean.TRUE);
            runButton.putClientProperty("JButton.buttonType", "toolBarButton");
        } catch (Throwable ignored) {
        }

        frame.add(toolbar, BorderLayout.NORTH);
        frame.add(vert, BorderLayout.CENTER);

        // Status bar (bottom)
        JPanel status = new JPanel(new BorderLayout());
        status.add(statusLabel, BorderLayout.CENTER);
        status.add(statusProgress, BorderLayout.EAST);
        status.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        frame.add(status, BorderLayout.SOUTH);

        // Tree interactions
        fileTree.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
            @Override
            public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
                Object last = event.getPath().getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode node) {
                    ensureChildrenLoaded(node);
                }
            }

            @Override
            public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) {
            }
        });

        fileTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                TreePath sel = fileTree.getSelectionPath();
                if (sel == null) return;
                Object last = sel.getLastPathComponent();
                if (!(last instanceof DefaultMutableTreeNode node)) return;
                Object uo = node.getUserObject();
                if (!(uo instanceof File f)) return;
                if (f.isDirectory()) return;

                FileObject fo = VirtualFileSystem.getInstance().find(f);
                if (fo == null) return;
                try {
                    tabs.openFile(fo);
                } catch (IOException ex) {
                    appendConsole("Failed to open: " + fo.getPath() + "\n" + ex + "\n");
                }
            }
        });

        // Run button
        runButton.addActionListener(e -> runProject());
    }

    private void renderIndexingStatus(IndexingProgressSnapshot snapshot) {
        if (snapshot == null) snapshot = new IndexingProgressSnapshot(0, 0, 0L, null);

        boolean dumb;
        try {
            dumb = DumbService.getInstance(project).isDumb();
        } catch (Throwable ignored) {
            dumb = false;
        }

        String mode = dumb ? "DUMB" : "SMART";

        String current = snapshot.getCurrentFilePath();
        if (current != null && !current.isBlank()) {
            try {
                current = Path.of(current).getFileName().toString();
            } catch (Throwable ignored) {
                // keep as-is
            }
        } else {
            current = "-";
        }

        int queued = snapshot.getQueuedFiles();
        int running = snapshot.getRunningFiles();
        boolean active = queued > 0 || running > 0;

        statusLabel.setText(
                "Mode: " + mode +
                        "   Indexing: queued=" + queued +
                        " running=" + running +
                        " done=" + snapshot.getCompletedFiles() +
                        " current=" + current
        );

        statusProgress.setVisible(active);
        statusProgress.setIndeterminate(active);
    }

    private void runProject() {
        runButton.setEnabled(false);
        console.setText("");

        // Prevent stale console output from previous runs.
        try {
            Path log = Path.of(project.getBuildDirectory().getChild("run").getChild("console.txt").getPath());
            Files.deleteIfExists(log);
        } catch (Throwable ignored) {
        }

        TasksService tasks = TasksService.getInstance(project);
        Task run = tasks.getTask("run");
        if (run == null) {
            appendConsole("No 'run' task registered. Apply plugin 'java' and set mainClass.\n");
            runButton.setEnabled(true);
            return;
        }

        CompletableFuture
                .supplyAsync(() -> tasks.run(run))
                .whenComplete((taskRun, err) -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (err != null) {
                            appendConsole("Run failed: " + renderError(project, err) + "\n");
                            return;
                        }

                        TaskResult result = taskRun.getResult(run);
                        if (result == null) {
                            appendConsole("Run finished, but no result was reported.\n");
                            return;
                        }

                        if (result.getStatus() == TaskResult.Status.FAILED) {
                            appendConsole("Run task failed: " + renderError(project, result.getError()) + "\n");
                        }

                        // If java plugin wrote a run log, display it.
                        if (result.getStatus() != TaskResult.Status.FAILED) {
                            String logPath = project.getBuildDirectory().getChild("run").getChild("console.txt").getPath();
                            Path p = Path.of(logPath);
                            if (Files.exists(p)) {
                                appendConsole(Files.readString(p, StandardCharsets.UTF_8));
                            }
                        }
                    } catch (Throwable t) {
                        appendConsole("Run UI error: " + renderError(project, t) + "\n");
                    } finally {
                        runButton.setEnabled(true);
                    }
                }));
    }

    private static String renderError(Project project, Throwable t) {
        if (t == null) return "(unknown error)";

        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        if (shouldShowStacktraces(project)) {
            // Explicitly enabled only; still keep it to a single line.
            return root.toString();
        }

        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return msg;
    }

    private static boolean shouldShowStacktraces(Project project) {
        // Explicit opt-in.
        String sys = System.getProperty("nanoj.desktop.showStacktraces");
        if (sys != null) {
            return Boolean.parseBoolean(sys.trim());
        }
        try {
            String p = project.getConfiguration().getProperty("nanoj.desktop.showStacktraces", "false");
            return Boolean.parseBoolean(p);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static volatile Theme DARK_THEME;

    private static Theme getDarkTheme() {
        Theme cached = DARK_THEME;
        if (cached != null) return cached;
        synchronized (DesktopIdeFrame.class) {
            cached = DARK_THEME;
            if (cached != null) return cached;
            try (var in = DesktopIdeFrame.class.getResourceAsStream(
                    "/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
                if (in == null) return null;
                DARK_THEME = Theme.load(in);
            } catch (Throwable ignored) {
                return null;
            }
            return DARK_THEME;
        }
    }

    private static Font resolveEditorFont(Project project, int size) {
        String preferredName = "JetBrains Mono";

        // 1) System-installed font
        try {
            Font f = new Font(preferredName, Font.PLAIN, size);
            if (f != null && preferredName.equalsIgnoreCase(f.getFamily())) {
                return f;
            }
        } catch (Throwable ignored) {
        }

        // 2) Load bundled font resource (no runtime network download)
        try {
            try (InputStream in = DesktopIdeFrame.class.getResourceAsStream("/fonts/JetBrainsMono-Regular.ttf")) {
                if (in != null) {
                    Font loaded = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont((float) size);
                    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(loaded);
                    return loaded;
                }
            }
        } catch (Throwable ignored) {
        }

        // 3) Fallback
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    private void appendConsole(String text) {
        console.append(text);
        console.setCaretPosition(console.getDocument().getLength());
    }

    private void ensureChildrenLoaded(DefaultMutableTreeNode node) {
        int childCount = node.getChildCount();
        if (childCount == 0) {
            populateChildren(node);
            return;
        }

        // If we previously inserted a placeholder child ("") to show an expand handle,
        // replace it with real children on first expansion.
        if (childCount == 1) {
            Object first = ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject();
            if (first instanceof String s && s.isEmpty()) {
                node.removeAllChildren();
                populateChildren(node);
            }
        }
    }

    private void populateChildren(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        if (!(uo instanceof File dir)) return;
        if (!dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        java.util.Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : files) {
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(f);
            node.add(child);
            if (f.isDirectory()) {
                // placeholder to show expand handle
                child.add(new DefaultMutableTreeNode(""));
            }
        }
        treeModel.nodeStructureChanged(node);
    }

    /**
     * Tab manager.
     */
    private static final class DesktopTabs {
        private final Project project;
        private final IndexManager indexManager;
        private final JTabbedPane tabs;

        // filePath -> component
        private final java.util.Map<String, EditorTab> openTabs = new java.util.HashMap<>();

        DesktopTabs(Project project, IndexManager indexManager, JTabbedPane tabs) {
            this.project = project;
            this.indexManager = indexManager;
            this.tabs = tabs;
        }

        void openFile(FileObject file) throws IOException {
            String path = file.getPath();
            EditorTab existing = openTabs.get(path);
            if (existing != null) {
                tabs.setSelectedComponent(existing.scroll);
                return;
            }

            EditorTab tab = EditorTab.create(project, indexManager, file);
            openTabs.put(path, tab);

            tabs.addTab(new File(path).getName(), tab.scroll);
            tabs.setSelectedComponent(tab.scroll);

            int idx = tabs.indexOfComponent(tab.scroll);
            tabs.setTabComponentAt(idx, new ClosableTabHeader(tabs, tab.scroll, new File(path).getName(), () -> openTabs.remove(path)));
        }
    }

    private static final class EditorTab {
        final FileObject file;
        final RSyntaxTextArea editor;
        final RTextScrollPane scroll;

        private EditorTab(FileObject file, RSyntaxTextArea editor, RTextScrollPane scroll) {
            this.file = file;
            this.editor = editor;
            this.scroll = scroll;
        }

        static EditorTab create(Project project, IndexManager indexManager, FileObject file) throws IOException {
            RSyntaxTextArea editor = new RSyntaxTextArea(30, 100);
            editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
            editor.setCodeFoldingEnabled(true);
            editor.setAntiAliasingEnabled(true);
            editor.setFractionalFontMetricsEnabled(true);
            editor.setFont(resolveEditorFont(project, 13));
            editor.setText(file.exists() ? file.getText() : "");

            Theme dark = getDarkTheme();
            if (dark != null) {
                try {
                    dark.apply(editor);
                } catch (Throwable ignored) {
                }
            }

            // Diagnostics (errors/warnings/info) via RSyntaxTextArea parser API.
            editor.addParser(new NanojDiagnosticsParser(project, file, editor));

            // Completion popup per-editor.
            new NanojCompletionPopup(project, file, editor);

            // Debounced save + reindex.
            Timer saveTimer = new Timer(350, e -> {
                try {
                    Files.writeString(Path.of(file.getPath()), editor.getText(), StandardCharsets.UTF_8);
                    indexManager.updateFile(file);
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

            RTextScrollPane scroll = new RTextScrollPane(editor);
            return new EditorTab(file, editor, scroll);
        }
    }

    private static final class FileTreeRenderer extends DefaultTreeCellRenderer {
        private final Project project;

        FileTreeRenderer(Project project) {
            this.project = project;
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean sel,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                Object uo = node.getUserObject();
                if (uo instanceof File f) {
                    String name = f.getName();
                    if (node.isRoot()) {
                        // Root: use project name or last folder segment.
                        String p = project != null ? project.getName() : null;
                        setText((p != null && !p.isBlank()) ? p : (name == null || name.isBlank() ? "(root)" : name));
                    } else {
                        setText((name == null || name.isBlank()) ? f.getPath() : name);
                    }
                } else if (uo instanceof String s && s.isEmpty()) {
                    setText("");
                }
            }

            return this;
        }
    }

    private static final class ClosableTabHeader extends JPanel {
        ClosableTabHeader(JTabbedPane tabs, Component tabComponent, String title, Runnable onClose) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            setOpaque(false);

            JLabel label = new JLabel(title);
            JButton close = new JButton("x");
            close.setMargin(new Insets(0, 4, 0, 4));
            close.addActionListener(e -> {
                int idx = tabs.indexOfComponent(tabComponent);
                if (idx >= 0) {
                    tabs.removeTabAt(idx);
                    if (onClose != null) onClose.run();
                }
            });

            add(label);
            add(close);
        }
    }
}
