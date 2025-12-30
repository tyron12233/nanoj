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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps fully-qualified class names to a file path/URI string.
 * <p>
 * This is similar in spirit to IntelliJ's "full class name" indexes (FQN -> VirtualFile),
 * but uses a serializable value ({@link String}) so it can be stored in NanoJ's MapDB-backed indexes.
 * Consumers can resolve the value via {@code VirtualFileSystem.getInstance().find(value)}.
 */
public final class JavaFullClassNameIndex implements IndexDefinition<String, String> {

    public static final String ID = "java_full_class_names";

    public static final int VERSION = 2;

    private final ParserFactory parserFactory;

    public static JavaFullClassNameIndex getInstance(Project project) {
        return ProjectServiceManager.getService(project, JavaFullClassNameIndex.class);
    }

    public JavaFullClassNameIndex(Project project) {
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
    public boolean supports(FileObject fileObject) {
        String extension = fileObject.getExtension();
        return "class".equals(extension) || "java".equals(extension);
    }

    @Override
    public Map<String, String> map(FileObject file, Object helper) {
        String ext = file.getExtension();
        if ("class".equals(ext)) {
            return mapBinary(file, helper);
        }
        return mapSource(file, helper);
    }

    @Override
    public boolean isValueForFile(String value, int fileId) {
        // Value is a path/URI; ForwardIndex owns the truth for cleanup.
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
        new SourceVisitor(results, file.getPath()).scan(unit, null);
        return results;
    }

    private static final class SourceVisitor extends TreeScanner<Void, Void> {
        private final Map<String, String> results;
        private final String filePath;

        private String packageName = "";
        private final Deque<String> classStack = new ArrayDeque<>();

        private SourceVisitor(Map<String, String> results, String filePath) {
            this.results = results;
            this.filePath = filePath;
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
                return super.visitClass(node, unused);
            }

            StringBuilder fqn = new StringBuilder();
            if (!packageName.isEmpty()) {
                fqn.append(packageName).append('.');
            }
            for (String enclosing : classStack) {
                fqn.append(enclosing).append('.');
            }
            fqn.append(simpleName);

            results.put(fqn.toString(), filePath);

            classStack.addLast(simpleName);
            super.visitClass(node, unused);
            classStack.removeLast();
            return null;
        }
    }

    private Map<String, String> mapBinary(FileObject file, Object helper) {
        try {
            ClassFile cf = SharedClassFile.get(file, helper);

            // raw name: java/util/Map$Entry
            String binaryName = cf.getName();

            // java.util.Map.Entry
            String fqn = binaryName.replace('/', '.').replace('$', '.');

            String lastSegment;
            int lastDot = fqn.lastIndexOf('.');
            lastSegment = lastDot == -1 ? fqn : fqn.substring(lastDot + 1);

            // Skip anonymous inner classes like "1"
            if (lastSegment.matches("\\d+")) {
                return Map.of();
            }

            return Map.of(fqn, file.getPath());
        } catch (ConstantPoolException | java.io.IOException e) {
            return Map.of();
        }
    }
}
