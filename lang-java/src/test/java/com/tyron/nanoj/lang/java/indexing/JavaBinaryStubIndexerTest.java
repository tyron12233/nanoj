package com.tyron.nanoj.lang.java.indexing;

import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.lang.java.indexing.stub.ClassStub;
import com.tyron.nanoj.testFramework.BaseJavaIndexingTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JavaBinaryStubIndexerTest extends BaseJavaIndexingTest {

    private JavaBinaryStubIndexer indexer;

    @BeforeEach
    public void initService() {
        indexer = new JavaBinaryStubIndexer(project);
        ProjectServiceManager.registerInstance(project, JavaBinaryStubIndexer.class, indexer);
    }

    @Test
    public void testBasicClassStructure() {
        byte[] classBytes = compile("com.example.Simple",
                "package com.example; public abstract class Simple {}");

        MockFileObject file = createClassFile("com.example.Simple", classBytes);

        Map<String, ClassStub> result = indexer.map(file, null);

        assertNotNull(result);
        ClassStub stub = result.get("com/example/Simple");
        assertEquals("com/example/Simple", stub.name);
        assertEquals("java/lang/Object", stub.superName);
        assertTrue((stub.accessFlags & 0x0400) != 0); // ACC_ABSTRACT
    }

    @Test
    public void testInterfacesAndInheritance() {
        byte[] classBytes = compile("com.example.Child",
                "package com.example; " +
                        "import java.io.Serializable; " +
                        "public class Child extends Thread implements Serializable, Cloneable {}");

        MockFileObject file = createClassFile("com.example.Child", classBytes);

        ClassStub stub = indexer.map(file, null).values().iterator().next();

        assertEquals("com/example/Child", stub.name);
        assertEquals("java/lang/Thread", stub.superName);

        List<String> ifaces = Arrays.asList(stub.interfaces);
        assertTrue(ifaces.contains("java/io/Serializable"));
    }

    @Test
    public void testGenericsSignatures() {
        byte[] classBytes = compile("com.example.Box",
                "package com.example; " +
                        "public class Box<T> { public T getValue() { return null; } }");

        MockFileObject file = createClassFile("com.example.Box", classBytes);

        ClassStub stub = indexer.map(file, null).values().iterator().next();

        assertNotNull(stub.signature);
        assertTrue(stub.signature.contains("<T:"));
    }

    @Test
    public void testInnerClasses() {
        // Compile multi returns a map of all generated classes
        Map<String, byte[]> allBytes = compileMulti("com.example.Outer",
                "package com.example; public class Outer { public static class Inner {} }");

        byte[] innerBytes = allBytes.get("com.example.Outer$Inner");
        assertNotNull(innerBytes);

        MockFileObject file = createClassFile("com.example.Outer$Inner", innerBytes);

        ClassStub stub = indexer.map(file, null).values().iterator().next();
        assertEquals("com/example/Outer$Inner", stub.name);
    }
}