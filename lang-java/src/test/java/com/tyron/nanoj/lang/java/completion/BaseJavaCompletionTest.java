package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.completion.CodeCompletionService;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementPresentation;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.core.indexing.SharedIndexBuilder;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.lang.java.JavaLanguageSupport;
import com.tyron.nanoj.lang.java.compiler.JavacFileManagerService;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.JavaFullClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;
import com.tyron.nanoj.lang.java.indexing.ShortClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaSuperTypeIndex;
import com.tyron.nanoj.lang.java.source.ParsingManager;
import com.tyron.nanoj.testFramework.BaseCompletionTest;
import com.tyron.nanoj.testFramework.FreshIndices;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assertions;

/**
 * Base for Java completion tests.
 *
 * Sets up:
 * - Editor + Completion core
 * - Java parsing services (ParsingManager, JavacFileManagerService)
 * - Java indices
 * - JavaLanguageSupport extension registration
 */
public abstract class BaseJavaCompletionTest extends BaseCompletionTest {

    private static final Logger LOG = Logger.getLogger(BaseJavaCompletionTest.class.getName());

    protected IndexManager indexManager;

    protected static final String CARET = "<caret>";

        private static final String JRT_MAX_CLASSES_PROP = "nanoj.test.jrt.maxClasses";

        // Keep this intentionally small: traversing the whole JRT image is expensive.
        private static final String[] DEFAULT_JRT_ROOTS = {
            "jrt:/modules/java.base/java/lang/",
            "jrt:/modules/java.base/java/util/",
            "jrt:/modules/java.base/java/io/",
            "jrt:/modules/java.base/java/time/"
        };

        private static final String TEST_USE_SHARED_INDEX_PROP = "nanoj.test.useSharedIndexes";
        private static final String TEST_SHARED_INDEX_DIR_PROP = "nanoj.test.sharedIndexDir";
        private static final String TEST_BUILD_SHARED_INDEX_PROP = "nanoj.test.buildSharedIndexes";
        private static final String TEST_SHARED_INDEX_SCHEMA_VERSION = "v1";

    @Override
        protected void beforeEach() throws Exception {
            super.beforeEach();

        Assumptions.assumeTrue(
                ToolProvider.getSystemJavaCompiler() != null,
                "These tests require a JDK (ToolProvider.getSystemJavaCompiler() != null)."
        );

        configureJavaProject();

        var libsDir = dir("libs");
        project.addLibrary(libsDir);

        List<IndexDefinition<?, ?>> javaDefs = List.of(
                new JavaBinaryStubIndexer(project),
                new ShortClassNameIndex(project),
                new JavaFullClassNameIndex(project),
                new JavaPackageIndex(project),
            new JavaSuperTypeIndex(project)
        );

        File sharedIndexFile = ensureSharedJdkIndex(javaDefs);

        indexManager = IndexManager.getInstance();
        for (IndexDefinition<?, ?> def : javaDefs) {
            indexManager.register(def);
        }


        ProjectServiceManager.registerInstance(project, JavacFileManagerService.class, new JavacFileManagerService(project));
        ProjectServiceManager.registerInstance(project, ParsingManager.class, new ParsingManager(project));
        ProjectServiceManager.registerExtension(project, LanguageSupport.class, JavaLanguageSupport.class);
    }

    @Override
    protected void afterEach() {
        try {
            if (indexManager != null) {
                indexManager.dispose();
                indexManager = null;
            }
        } finally {
            super.afterEach();
        }
    }

    // =========================
    //        Utilities
    // =========================

    protected FileObject javaFile(String fqcn, String content) {
        return java(fqcn, content);
    }

    private File ensureSharedJdkIndex(List<IndexDefinition<?, ?>> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }

        if (this.getClass().isAnnotationPresent(FreshIndices.class)) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.info("sharedIndex action=skip reason=freshIndices testClass=" + this.getClass().getName());
            }
            return null;
        }

        boolean enabled = Boolean.parseBoolean(System.getProperty(TEST_USE_SHARED_INDEX_PROP, "true"));
        if (!enabled) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.info("sharedIndex action=skip reason=disabled prop=" + TEST_USE_SHARED_INDEX_PROP);
            }
            return null;
        }

        int maxClasses = readIntProperty(JRT_MAX_CLASSES_PROP, Integer.MAX_VALUE);

        File sharedDir = sharedIndexDir();
        if (!sharedDir.exists() && !sharedDir.mkdirs()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("sharedIndex action=skip reason=mkdirFailed dir=" + sharedDir);
            }
            return null;
        }

        String javaVersion = sanitizeForFilename(System.getProperty("java.version", "unknown"));
        File outFile = new File(sharedDir,
                "nanoj-jdk-" + javaVersion + "-max" + maxClasses + "-" + TEST_SHARED_INDEX_SCHEMA_VERSION + ".db");

        if (LOG.isLoggable(Level.INFO)) {
            LOG.info("sharedIndex candidate file=" + outFile + " exists=" + outFile.isFile() + " javaVersion=" + javaVersion + " maxClasses=" + maxClasses);
        }

        // Build once if missing.
        if (outFile.isFile() && outFile.length() > 0) {
            return outFile;
        }

        boolean buildIfMissing = Boolean.parseBoolean(System.getProperty(TEST_BUILD_SHARED_INDEX_PROP, "true"));
        if (!buildIfMissing) {
            // Shared DB not present; fall back to per-test local indexing.
            if (LOG.isLoggable(Level.INFO)) {
                LOG.info("sharedIndex action=skip reason=missing buildIfMissing=false prop=" + TEST_BUILD_SHARED_INDEX_PROP);
            }
            return null;
        }

        File lockFile = new File(sharedDir, outFile.getName() + ".lock");

        try (FileChannel ch = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        )) {
            try (FileLock ignored = ch.lock()) {
                if (outFile.isFile() && outFile.length() > 0) {
                    return outFile;
                }

                long t0 = System.nanoTime();
                if (LOG.isLoggable(Level.INFO)) {
                    LOG.info("sharedIndex action=build start file=" + outFile + " lock=" + lockFile);
                }

                List<FileObject> files = collectJrtClassFiles(maxClasses);
                if (files.isEmpty()) {
                    if (LOG.isLoggable(Level.WARNING)) {
                        LOG.warning("sharedIndex action=build result=fail reason=noJrtFiles");
                    }
                    return null;
                }

                if (LOG.isLoggable(Level.INFO)) {
                    LOG.info("sharedIndex action=build collectedFiles count=" + files.size());
                }

                File tmp = new File(sharedDir, outFile.getName() + ".tmp");
                if (tmp.exists()) {
                    try {
                        // Best effort cleanup.
                        Files.delete(tmp.toPath());
                    } catch (Throwable ignored2) {
                    }
                }

                SharedIndexBuilder.build(tmp, files, definitions);

                Files.move(tmp.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                boolean ok = outFile.isFile() && outFile.length() > 0;
                if (LOG.isLoggable(Level.INFO)) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    LOG.info("sharedIndex action=build result=" + (ok ? "ok" : "fail") + " tookMs=" + ms + " file=" + outFile);
                }
                return ok ? outFile : null;
            }
        } catch (Throwable t) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Shared index build failed", t);
            }
            return null;
        }
    }

    private static File sharedIndexDir() {
        String configured = System.getProperty(TEST_SHARED_INDEX_DIR_PROP);
        if (configured != null && !configured.isBlank()) {
            return new File(configured.trim());
        }
        return new File("shared-indexes", "nanoj-shared-indexes");
    }

    private List<FileObject> collectJrtClassFiles(int maxClasses) {
        int[] remaining = new int[]{Math.max(0, maxClasses)};
        List<FileObject> toIndex = new ArrayList<>(Math.min(remaining[0], 4096));

        for (String root : DEFAULT_JRT_ROOTS) {
            if (remaining[0] <= 0) {
                break;
            }
            try {
                FileObject pkgRoot = VirtualFileManager.getInstance().find(URI.create(root));
                collectClassFilesRecursively(pkgRoot, remaining, toIndex);
            } catch (Throwable ignored) {
                break;
            }
        }

        return toIndex;
    }

    private static String sanitizeForFilename(String s) {
        if (s == null || s.isBlank()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    protected static void collectClassFilesRecursively(FileObject dir, int[] remainingClasses, List<FileObject> out) {
        if (dir == null || out == null) {
            return;
        }

        // Avoid per-child exists() calls; for jrt: those can be surprisingly expensive.
        if (!dir.isFolder()) {
            return;
        }

        List<FileObject> children = dir.getChildren();
        if (children == null || children.isEmpty()) {
            return;
        }

        for (FileObject c : children) {
            if (c == null) continue;
            if (remainingClasses != null && remainingClasses[0] <= 0) {
                return;
            }
            if (c.isFolder()) {
                collectClassFilesRecursively(c, remainingClasses, out);
            } else if ("class".equalsIgnoreCase(c.getExtension())) {
                out.add(c);
                if (remainingClasses != null) {
                    remainingClasses[0]--;
                }
            }
        }
    }

    private static int readIntProperty(String key, int defaultValue) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.isBlank()) return defaultValue;
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    protected CompletionResult completeAtCaret(FileObject file, String textWithCaret) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(textWithCaret, "textWithCaret");

        int caret = textWithCaret.indexOf(CARET);
        if (caret < 0) {
            throw new IllegalArgumentException("Missing " + CARET + " marker");
        }
        if (textWithCaret.indexOf(CARET, caret + 1) >= 0) {
            throw new IllegalArgumentException("Multiple " + CARET + " markers are not supported");
        }

        String text = textWithCaret.substring(0, caret) + textWithCaret.substring(caret + CARET.length());
        return complete(file, text, caret);
    }

    protected CompletionResult complete(FileObject file, String text, int offset) {
        CodeCompletionService service = ProjectServiceManager.getService(project, CodeCompletionService.class);
        List<LookupElement> items = service.getCompletions(file, text, offset);
        return new CompletionResult(text, offset, items);
    }

    protected static LookupElementPresentation render(LookupElement element) {
        LookupElementPresentation p = new LookupElementPresentation();
        p.clear();
        element.renderElement(p);
        p.freeze();
        return p;
    }

    protected static LookupElement requireLookup(List<LookupElement> items, String lookupString) {
        for (LookupElement e : items) {
            if (lookupString.equals(e.getLookupString())) {
                return e;
            }
        }
        Assertions.fail("Expected lookup element not found: '" + lookupString + "'. Got: " + debugLookups(items));
        return null;
    }

    protected static void assertHasLookup(List<LookupElement> items, String lookupString) {
        requireLookup(items, lookupString);
    }

    protected static void assertPresentation(
            LookupElement element,
            String expectedItemText,
            String expectedIconKey,
            String expectedTailTextContains,
            String expectedTypeText
    ) {
        LookupElementPresentation p = render(element);

        if (expectedItemText != null) {
            Assertions.assertEquals(expectedItemText, p.getItemText(), "itemText");
        }
        if (expectedIconKey != null) {
            Assertions.assertEquals(expectedIconKey, p.getIconKey(), "iconKey");
        }
        if (expectedTailTextContains != null) {
            Assertions.assertNotNull(p.getTailText(), "tailText should not be null");
            Assertions.assertTrue(
                    p.getTailText().contains(expectedTailTextContains),
                    "tailText should contain '" + expectedTailTextContains + "' but was: '" + p.getTailText() + "'"
            );
        }
        if (expectedTypeText != null) {
            Assertions.assertEquals(expectedTypeText, p.getTypeText(), "typeText");
        }
    }

    protected static void assertStrikeout(LookupElement element, boolean expectedStrikeout) {
        LookupElementPresentation p = render(element);
        Assertions.assertEquals(expectedStrikeout, p.isItemTextStrikeout(), "strikeout");
    }

    protected static String debugLookups(List<LookupElement> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i).getLookupString());
        }
        sb.append("]");
        return sb.toString();
    }

    protected static final class CompletionResult {
        public final String text;
        public final int offset;
        public final List<LookupElement> items;

        CompletionResult(String text, int offset, List<LookupElement> items) {
            this.text = text;
            this.offset = offset;
            this.items = items;
        }
    }
}
