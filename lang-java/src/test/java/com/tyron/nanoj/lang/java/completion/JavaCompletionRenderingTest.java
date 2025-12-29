package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.vfs.FileObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class JavaCompletionRenderingTest extends BaseJavaCompletionTest {

    @Test
    void rendersMethodWithParamsAndReturnType() {
        FileObject file = javaFile("p.Foo",
                "package p;\n" +
                "public class Foo {\n" +
                "  int field;\n" +
                "  String method(int param, String name) { return name; }\n" +
                "  void test() {\n" +
                "    this.met<caret>();\n" +
                "  }\n" +
                "}\n");

        CompletionResult r = completeAtCaret(file,
                "package p;\n" +
                "public class Foo {\n" +
                "  int field;\n" +
                "  String method(int param, String name) { return name; }\n" +
                "  void test() {\n" +
                "    this.met<caret>();\n" +
                "  }\n" +
                "}\n");

        List<LookupElement> items = r.items;
        assertFalse(items.isEmpty());

        LookupElement method = requireLookup(items, "method");
        assertPresentation(method, "method", "method", "(", "String");
    }

    @Test
    void rendersFieldWithType() {
        FileObject file = javaFile("p.Foo",
                "package p;\n" +
                "public class Foo {\n" +
                "  int field;\n" +
                "  void test() {\n" +
                                "    int x = this.fie<caret>;\n" +
                "  }\n" +
                "}\n");

        CompletionResult r = completeAtCaret(file,
                "package p;\n" +
                "public class Foo {\n" +
                "  int field;\n" +
                "  void test() {\n" +
                "    int x = this.fie<caret>;\n" +
                "  }\n" +
                "}\n");

        LookupElement field = requireLookup(r.items, "field");
        assertPresentation(field, "field", "field", null, "int");
    }

    @Test
    void rendersLocalAndParameterKinds() {
        FileObject file = javaFile("p.Foo",
                "package p;\n" +
                "public class Foo {\n" +
                "  void test(int param) {\n" +
                "    int localVar = 0;\n" +
                                "    int y = loc<caret>;\n" +
                "  }\n" +
                "}\n");

        CompletionResult local = completeAtCaret(file,
                "package p;\n" +
                "public class Foo {\n" +
                "  void test(int param) {\n" +
                "    int localVar = 0;\n" +
                "    int y = loc<caret>;\n" +
                "  }\n" +
                "}\n");

        LookupElement localVar = requireLookup(local.items, "localVar");
        assertPresentation(localVar, "localVar", "variable", null, "int");

        CompletionResult param = completeAtCaret(file,
                "package p;\n" +
                "public class Foo {\n" +
                "  void test(int param) {\n" +
                "    int localVar = 0;\n" +
                "    int y = par<caret>;\n" +
                "  }\n" +
                "}\n");

        LookupElement p = requireLookup(param.items, "param");
        assertPresentation(p, "param", "parameter", null, "int");
    }

    @Test
    void deprecatedIsStrikeout() {
        FileObject file = javaFile("p.Foo",
                "package p;\n" +
                "public class Foo {\n" +
                "  @Deprecated String oldField;\n" +
                "  void test() {\n" +
                                "    String s = old<caret>;\n" +
                "  }\n" +
                "}\n");

        CompletionResult r = completeAtCaret(file,
                "package p;\n" +
                "public class Foo {\n" +
                "  @Deprecated String oldField;\n" +
                "  void test() {\n" +
                "    String s = old<caret>;\n" +
                "  }\n" +
                "}\n");

        LookupElement old = requireLookup(r.items, "oldField");
        assertStrikeout(old, true);
    }
}
