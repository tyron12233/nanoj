package com.tyron.nanoj.testFramework;

import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.core.vfs.VirtualFileManager;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public abstract class BaseJavaIndexingTest extends BaseIdeTest {

    /**
     * Compiles a single Java source file in memory and returns the bytecode of the primary class.
     *
     * @param className The fully qualified class name (e.g., "com.example.MyClass")
     * @param source    The actual Java source code.
     * @return The bytecode array.
     */
    protected byte[] compile(String className, String source) {
        return compileMulti(className, source).values().iterator().next();
    }

    /**
     * Compiles Java source that may result in multiple class files (e.g., Inner classes).
     *
     * @return A map of "BinaryName" -> "Bytecode".
     */
    protected Map<String, byte[]> compileMulti(String mainClassName, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager stdManager = compiler.getStandardFileManager(null, null, null);
        MemoryFileManager fileManager = new MemoryFileManager(stdManager);

        JavaFileObject sourceFile = new SimpleJavaFileObject(
                URI.create("string:///" + mainClassName.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, null, null, null, Collections.singletonList(sourceFile)
        );

        boolean success = task.call();
        if (!success) {
            throw new RuntimeException("Compilation failed for test source:\n" + source);
        }

        return fileManager.classes;
    }

    /**
     * Helper to create a .class file in the Mock Project /libs directory.
     */
    protected MockFileObject createClassFile(String binaryName, byte[] content) {
        String path = "/libs/" + binaryName.replace('.', '/') + ".class";

        MockFileObject fo = new MockFileObject(path, content);
        fs.registerFile(fo);

        VirtualFileManager.getInstance().fireFileCreated(fo);
        return fo;
    }


    private static class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        final Map<String, byte[]> classes = new HashMap<>();

        MemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + ".class"), JavaFileObject.Kind.CLASS) {
                @Override
                public OutputStream openOutputStream() {
                    return new ByteArrayOutputStream() {
                        @Override
                        public void close() throws IOException {
                            classes.put(className, this.toByteArray());
                        }
                    };
                }
            };
        }
    }
}