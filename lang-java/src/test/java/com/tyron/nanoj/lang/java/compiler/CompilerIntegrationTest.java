package com.tyron.nanoj.lang.java.compiler;

import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.lang.java.indexing.JavaSuperTypeIndex;
import com.tyron.nanoj.lang.java.indexing.ShortClassNameIndex;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.util.*;

public class CompilerIntegrationTest extends BaseIdeTest {

    @Override
    protected void beforeEach() {
        var indexManager = IndexManager.getInstance(project);

        indexManager.register(new JavaBinaryStubIndexer(project));
        indexManager.register(new ShortClassNameIndex(project));
        indexManager.register(new JavaPackageIndex(project));
        indexManager.register(new JavaSuperTypeIndex(project));
    }

    /**
     * Scenario:
     * 1. LibClass has a method hello().
     * 2. compile LibClass and index it.
     * 3. AppClass uses LibClass.
     * 4. We compile AppClass WITHOUT LibClass on the file system classpath.
     * 5. Success proves Javac used our Index -> StubCompleter.
     */
    @Test
    public void testBasicDependencyResolution() {
        compileAndIndex("Lib",
                "package com.example; " +
                "public class Lib { " +
                "   public String hello() { return \"World\"; } " +
                "}");

        boolean success = compileApp("App",
                "package com.example; " +
                "public class App { " +
                "   void main() { " +
                "       Lib l = new Lib(); " +
                "   } " +
                "}");

        Assertions.assertTrue(success, "Compilation should succeed using Stubs");
    }

    /**
     * Scenario: Generics.
     * If StubTypeResolver fails to parse signatures, Javac will treat Box as a raw type.
     * Then `String s = box.get()` would fail (Object cannot be cast to String).
     */
    @Test
    public void testGenericSignatureResolution() {
        compileAndIndex("com.example.Box",
                "package com.example; " +
                "public class Box<T> { " +
                "   private T value; " +
                "   public Box(T v) { this.value = v; } " +
                "   public T get() { return value; } " +
                "}");

        boolean success = compileApp("com.example.App",
                "package com.example; " +
                "public class App { " +
                "   void test() { " +
                "       Box<String> b = new Box<>(\"Hello\"); " +
                "       String s = b.get(); " +
                "   } " +
                "}");

        Assertions.assertTrue(success, "Generics should be resolved from Index Signature");
    }

    @Test
    public void testInheritanceAndInterfaces() {
        compileAndIndex("com.example.Cleanable", "package com.example; public interface Cleanable { void clean(); }");
        compileAndIndex("com.example.Base", "package com.example; public abstract class Base { public abstract void baseMethod(); }");

        boolean success = compileApp("com.example.Impl",
                "package com.example; " +
                "public class Impl extends Base implements Cleanable { " +
                "   @Override public void clean() {} " +
                "   @Override public void baseMethod() {} " +
                "}");

        Assertions.assertTrue(success, "Should resolve hierarchy from Stubs");
    }

    /**
     * Scenario: Package Scanning (Star Import).
     * `import com.util.*;` triggers `IndexedJavaFileManager.list()`.
     */
    @Test
    public void testStarImportPackageScanning() {
        compileAndIndex("com.util.HelperA", "package com.util; public class HelperA {}");
        compileAndIndex("com.util.HelperB", "package com.util; public class HelperB {}");

        boolean success = compileApp("com.main.Main",
                "package com.main; " +
                "import com.util.*; " + // <--- Triggers list("com.util")
                "public class Main { " +
                "   HelperA a; " +
                "   HelperB b; " +
                "}");

        Assertions.assertTrue(success, "Star imports should be resolved via Index Package Listing");
    }

    // =================================================================================
    //                                   HELPERS
    // =================================================================================

    /**
     * Compiles source to bytecode in memory, then manually feeds it to the IndexManager.
     * This simulates an external library (JAR) being indexed.
     */
    private void compileAndIndex(String className, String sourceCode) {
        Map<String, byte[]> compliedClasses = compileInMemory(className, sourceCode);

        for (Map.Entry<String, byte[]> entry : compliedClasses.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();

            // Create a Mock File in VFS so the IndexManager can "see" it
            // Javac needs the file to exist in VFS when we look it up later
            String fakePath =  "libs/" + name.replace('.', '/') + ".class";
            MockFileObject vfsFile = file(fakePath, bytes);
            project.addLibrary(vfsFile);
        }

        IndexManager.getInstance(project).flush();
    }

    /**
     * Compiles the "User App" code.
     * This uses our custom `IndexedJavaFileManager` and `IndexAwareClassFinder`.
     */
    private boolean compileApp(String className, String sourceCode) {
        JavacTool compiler = (JavacTool) ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager stdManager = compiler.getStandardFileManager(null, null, null);
        
        Context context = new Context();

        IndexAwareClassFinder.preRegister(context, project);

        IndexedJavaFileManager indexedManager = new IndexedJavaFileManager(stdManager, project);

        JavaFileObject sourceFile = new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"), 
                JavaFileObject.Kind.SOURCE
        ) {
            @Override public CharSequence getCharContent(boolean b) { return sourceCode; }
        };

        var task = compiler.getTask(
                null, // Writer
                indexedManager, 
                null,
                List.of("-proc:none"),
                null, // Classes
                Collections.singletonList(sourceFile), 
                context
        );

        return task.call();
    }

    private Map<String, byte[]> compileInMemory(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager stdManager = compiler.getStandardFileManager(null, null, null);

        MemoryFileManager memManager = new MemoryFileManager(stdManager);

        JavaFileObject src = new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean b) { return source; }
        };

        Boolean success = compiler.getTask(null, memManager, null, null, null, Collections.singletonList(src)).call();

        if (!Boolean.TRUE.equals(success)) {
            throw new RuntimeException("Compilation failed for test helper: " + className);
        }

        return memManager.classes;
    }


    static class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        // Store compiled bytes: ClassName -> Bytes
        final Map<String, byte[]> classes = new HashMap<>();

        MemoryFileManager(StandardJavaFileManager m) {
            super(m);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, javax.tools.FileObject sibling) throws IOException {
            // Create an in-memory JavaFileObject
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + ".class"), kind) {

                @Override
                public OutputStream openOutputStream() throws IOException {
                    return new ByteArrayOutputStream() {
                        @Override
                        public void close() throws IOException {
                            // Capture the bytes when the compiler is done writing
                            classes.put(className, toByteArray());
                        }
                    };
                }

                @Override
                public InputStream openInputStream() throws IOException {
                    // Allow reading back the bytes we just wrote (if Javac needs to)
                    byte[] bytes = classes.get(className);
                    if (bytes == null) {
                        throw new FileNotFoundException("Class not found in memory: " + className);
                    }
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }

    private void deleteRecursive(File f) { if(f.isDirectory()) for(File c:f.listFiles()) deleteRecursive(c); f.delete(); }
}