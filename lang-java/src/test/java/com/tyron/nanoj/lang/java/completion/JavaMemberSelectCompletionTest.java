package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.vfs.FileObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaMemberSelectCompletionTest extends BaseJavaCompletionTest {

    @Test
    void memberSelectCompletesInstanceMembers() {
        String textWithCaret = """
                package p;
                public class Foo {
                  void test() {
                    String s = \"hi\";
                    s.<caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertFalse(r.items.isEmpty(), "Expected some completions");
        assertHasLookup(r.items, "toString");
        assertHasLookup(r.items, "length");
    }

    @Test
    void memberSelectOnParameterizedTypeSubstitutesGenerics() {
        String textWithCaret = """
                package p;
                import java.util.List;
                public class Foo {
                  void test(List<String> list) {
                    list.<caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        // List<E>.get(int) should render as String for List<String>.
        var get = requireLookup(r.items, "get");
        assertPresentation(get, "get", "method", "(int", "String");
    }

    @Test
    void newExpressionSuggestsSubclassesOfExpectedType() {
        String textWithCaret = """
                package p;
                import java.util.List;
                public class Foo {
                  void test() {
                    List<String> list = new <caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        // Should prefer common List implementations.
        assertHasLookup(r.items, "ArrayList");
        assertHasLookup(r.items, "LinkedList");
    }

    @Test
    void memberSelectOnThisInStaticContextHasNoCompletions() {
        String textWithCaret = """
                package p;
                public class Foo {
                  static void test() {
                    this.<caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertTrue(r.items.isEmpty(), "Expected no completions for this. in static context");
    }

    @Test
    void memberSelectOnSuperInStaticContextHasNoCompletions() {
        String textWithCaret = """
                package p;
                public class Foo extends Bar {
                  static void test() {
                    super.<caret>
                  }
                }
                class Bar {
                  void instanceMethod() {}
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertTrue(r.items.isEmpty(), "Expected no completions for super. in static context");
    }
}
