package com.tyron.nanoj.lang.java.indexing;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The "Go to Class" Index.
 * <p>
 * Aggregates both Source (.java) and Binary (.class) files into a single lookup.
 * <p>
 * <b>Key:</b> Simple Name (e.g., "ArrayList", "Entry")<br>
 * <b>Value:</b> Fully Qualified Name (e.g., "java.util.ArrayList", "java.util.Map.Entry")
 * </p>
 */
public class ShortClassNameIndex implements IndexDefinition<String, String> {

    public static final String ID = "java_short_names";
    private static final int VERSION = 1;

    private final ParserFactory parserFactory;

    public static ShortClassNameIndex getInstance(Project project) {
        return ProjectServiceManager.getService(project, ShortClassNameIndex.class);
    }

    public ShortClassNameIndex(Project project) {
        // Shared Javac Context for parsing source files
        Context context = new Context();
        new JavacFileManager(context, true, StandardCharsets.UTF_8);
        this.parserFactory = ParserFactory.instance(context);
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
        String ext = file.getExtension();
        return "java".equals(ext) || "class".equals(ext);
    }

    @Override
    public Map<String, String> map(FileObject file, Object helper) {
        if ("class".equals(file.getExtension())) {
            return mapBinary(file, helper);
        } else {
            return mapSource(file, helper);
        }
    }

    private Map<String, String> mapSource(FileObject file, Object helper) {
        JCTree.JCCompilationUnit unit;

        if (helper instanceof JCTree.JCCompilationUnit) {
            unit = (JCTree.JCCompilationUnit) helper;
        } else {
            try {
                CharSequence content = file.getText();
                JavacParser parser = parserFactory.newParser(content, false, true, false);
                unit = parser.parseCompilationUnit();
            } catch (Throwable e) {
                return new HashMap<>();
            }
        }

        Map<String, String> results = new HashMap<>();
        new SourceVisitor(results).scan(unit, null);
        return results;
    }

    private static class SourceVisitor extends TreeScanner<Void, Void> {
        private final Map<String, String> results;
        private String packageName = "";
        private final Deque<String> classStack = new ArrayDeque<>();

        SourceVisitor(Map<String, String> results) {
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
            if (simpleName.isEmpty()) return super.visitClass(node, unused);

            StringBuilder fqn = new StringBuilder();
            if (!packageName.isEmpty()) fqn.append(packageName).append('.');

            for (String parent : classStack) fqn.append(parent).append('.');
            fqn.append(simpleName);

            // Index: SimpleName -> FQN
            results.put(simpleName, fqn.toString());

            classStack.addLast(simpleName);
            super.visitClass(node, unused);
            classStack.removeLast();
            return null;
        }
    }

    private Map<String, String> mapBinary(FileObject file, Object helper) {
        Map<String, String> results = new HashMap<>();
        try {
            ClassFile cf = SharedClassFile.get(file, helper);

            // raw name: java/util/Map$Entry
            String binaryName = cf.getName();

            // convert to Dot notation: java.util.Map.Entry
            // we replace '$' with '.' for better UI readability, though reflection uses '$'
            String fqn = binaryName.replace('/', '.').replace('$', '.');

            // Extract Simple Name
            String simpleName;
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot != -1) {
                simpleName = fqn.substring(lastDot + 1);
            } else {
                simpleName = fqn;
            }

            // Skip anonymous inner classes (usually numbers like "1")
            if (!simpleName.matches("\\d+")) {
                results.put(simpleName, fqn);
            }

        } catch (IOException | ConstantPoolException e) {
            // Corrupt class file or read error, skip
        }
        return results;
    }

    @Override
    public boolean isValueForFile(String value, int fileId) {
        // Since Value is just the FQN string, we can't prove origin strictly.
        // Returning true is safe for "remove-before-update" logic in IndexManager.
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
}