package com.tyron.nanoj.lang.java.indexing;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Indexes Java class declarations.
 * <p>
 * <b>Key:</b> Simple Class Name (e.g., "ArrayList")<br>
 * <b>Value:</b> Fully Qualified Name (e.g., "java.util.ArrayList")
 * </p>
 */
public class JavaClassIndexer implements IndexDefinition<String, String> {

    public static final String ID = "java_classes";
    private static final int VERSION = 1;

    private final Project project;
    private final ParserFactory parserFactory;

    public JavaClassIndexer(Project project) {
        this.project = project;

        Context context = new Context();
        // FileManager is required by ParserFactory even if we don't resolve symbols
        new JavacFileManager(context, true, StandardCharsets.UTF_8);
        this.parserFactory = ParserFactory.instance(context);
    }

    public static JavaClassIndexer getInstance(Project project) {
        return ProjectServiceManager.getService(project, JavaClassIndexer.class);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public boolean supports(FileObject file) {
        return "java".equals(file.getExtension());
    }

    @Override
    public Map<String, String> map(FileObject file, Object helper) {
        JCTree.JCCompilationUnit unit;

        // Optimization: Use pre-parsed AST if available (from Editor)
        if (helper instanceof JCTree.JCCompilationUnit) {
            unit = (JCTree.JCCompilationUnit) helper;
        } else {
            // Fallback: Parse from disk
            try {
                CharSequence content = file.getText();
                // keepDocComments=false, keepLineMap=true (need positions eventually)
                JavacParser parser = parserFactory.newParser(content, false, true, false);
                unit = parser.parseCompilationUnit();
            } catch (IOException e) {
                // Return empty map on IO failure so other indexers can proceed
                return new HashMap<>();
            }
        }

        Map<String, String> results = new HashMap<>();
        new ClassVisitor(results).scan(unit, null);
        return results;
    }

    /**
     * Determines if a value belongs to a specific file ID.
     * <p>
     * Since our Value is just a String (FQN), we cannot strictly prove it came from this file ID
     * without storing the FileID in the value. However, for the "Remove Stale Entries" phase,
     * returning true is generally safe because the IndexManager only calls this on values
     * derived from keys KNOWN to be associated with this file via the ForwardIndex.
     * </p>
     */
    @Override
    public boolean isValueForFile(String value, int fileId) {
        return true;
    }

    @Override
    public byte[] serializeKey(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] serializeValue(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String deserializeKey(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public String deserializeValue(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    // --- AST Visitor ---

    private static class ClassVisitor extends TreeScanner<Void, Void> {

        private final Map<String, String> results;
        private String packageName = "";

        // Stack to track nesting: [Outer, Inner]
        private final Deque<String> classStack = new ArrayDeque<>();

        ClassVisitor(Map<String, String> results) {
            this.results = results;
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree node, Void unused) {
            if (node.getPackageName() != null) {
                packageName = node.getPackageName().toString();
            }
            return super.visitCompilationUnit(node, unused);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String simpleName = node.getSimpleName().toString();

            if (simpleName.isEmpty()) {
                // anonymous classes (no name) are skipped for the index
                return super.visitClass(node, unused);
            }

            // construct FQN
            StringBuilder fqn = new StringBuilder();
            if (!packageName.isEmpty()) {
                fqn.append(packageName).append('.');
            }
            for (String enclosing : classStack) {
                fqn.append(enclosing).append('.');
            }
            fqn.append(simpleName);

            results.put(simpleName, fqn.toString());

            classStack.push(simpleName);
            super.visitClass(node, unused);
            classStack.pop();

            return null;
        }
    }
}