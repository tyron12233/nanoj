package com.tyron.nanoj.lang.java.indexing;

import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.testFramework.BaseJavaIndexingTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class JavaFullClassNameIndexTest extends BaseJavaIndexingTest {

    private JavaFullClassNameIndex index;

    @BeforeEach
    public void initService() {
        index = new JavaFullClassNameIndex(project);
        ProjectServiceManager.registerInstance(project, JavaFullClassNameIndex.class, index);
    }

    @Test
    public void testParseJavaSource() {
        MockFileObject file = file(
                "src/com/foo/Bar.java",
                "package com.foo;\n" +
                        "public class Bar {\n" +
                        "  class Inner {}\n" +
                        "}\n"
        );

        Map<String, String> map = index.map(file, null);

        Assertions.assertEquals(file.getPath(), map.get("com.foo.Bar"));
        Assertions.assertEquals(file.getPath(), map.get("com.foo.Bar.Inner"));
    }

    @Test
    public void testParseBinaryClass_outerAndInner() {
        Map<String, byte[]> compiled = compileMulti(
                "com.example.Demo",
                "package com.example; public class Demo { public static class Inner {} }"
        );

        byte[] outer = compiled.get("com.example.Demo");
        byte[] inner = compiled.get("com.example.Demo$Inner");
        Assertions.assertNotNull(outer);
        Assertions.assertNotNull(inner);

        MockFileObject outerFile = createClassFile("com.example.Demo", outer);
        MockFileObject innerFile = createClassFile("com.example.Demo$Inner", inner);

        Map<String, String> outerMap = index.map(outerFile, null);
        Map<String, String> innerMap = index.map(innerFile, null);

        Assertions.assertEquals(outerFile.getPath(), outerMap.get("com.example.Demo"));
        Assertions.assertEquals(innerFile.getPath(), innerMap.get("com.example.Demo.Inner"));
    }
}
