package com.tyron.nanoj.lang.java.indexing;

import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.testFramework.BaseJavaIndexingTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class ShortClassNameIndexTest extends BaseJavaIndexingTest {

    private ShortClassNameIndex indexer;

    @BeforeEach
    public void initService() {
        indexer = new ShortClassNameIndex(project);
        ProjectServiceManager.registerInstance(project, ShortClassNameIndex.class, indexer);
    }

    @Test
    public void testParseJavaSource() {
        MockFileObject file = file("src/com/foo/Bar.java",
                "package com.foo; \n" +
                        "public class Bar { \n" +
                        "   class Inner {} \n" +
                        "}");

        Map<String, String> map = indexer.map(file, null);

        Assertions.assertEquals("com.foo.Bar", map.get("Bar"));
        Assertions.assertEquals("com.foo.Bar.Inner", map.get("Inner"));
    }

    @Test
    public void testParseBinaryClass() {
        byte[] bytes = compile("com.example.Demo", "package com.example; public class Demo {}");

        MockFileObject file = createClassFile("com.example.Demo", bytes);

        Map<String, String> map = indexer.map(file, null);
        Assertions.assertEquals("com.example.Demo", map.get("Demo"));
    }
}