package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.vfs.FileObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaNewExpressionCompletionTest extends BaseJavaCompletionTest {

    @Test
    void newExpressionSuggestsSubclassesFromReturnType() {
        String textWithCaret = """
                package p;
                import java.util.List;
                public class Foo {
                  List<String> make() {
                    return new <caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "ArrayList");
        assertHasLookup(r.items, "LinkedList");
    }

    @Test
    void newExpressionSuggestsSubclassesFromMethodArgumentType() {
        String textWithCaret = """
                package p;
                import java.util.List;
                public class Foo {
                  void take(List<String> list) {}
                  void test() {
                    take(new <caret>);
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "ArrayList");
        assertHasLookup(r.items, "LinkedList");
    }

    @Test
    void newExpressionUsesCastTypeForExpectedType() {
        String textWithCaret = """
                package p;
                import java.util.List;
                public class Foo {
                  void test() {
                    Object o = (List<String>) new <caret>;
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "ArrayList");
        assertHasLookup(r.items, "LinkedList");
    }

      @Test
      void newExpressionPrefersConcreteOverAbstract() {
        String textWithCaret = """
            package p;
            import java.util.List;
            public class Foo {
              List<String> make() {
              return new <caret>
              }
            }
            """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "ArrayList");
        assertHasLookup(r.items, "AbstractList");

        int arrayListIndex = indexOf(r.items, "ArrayList");
        int abstractListIndex = indexOf(r.items, "AbstractList");
        assertTrue(arrayListIndex >= 0 && abstractListIndex >= 0, "Missing lookups got=" + debugLookups(r.items));
        assertTrue(arrayListIndex < abstractListIndex, "Expected 'ArrayList' before 'AbstractList' but got=" + debugLookups(r.items));
      }

      private static int indexOf(List<com.tyron.nanoj.api.completion.LookupElement> items, String lookup) {
        for (int i = 0; i < items.size(); i++) {
          if (lookup.equals(items.get(i).getLookupString())) {
            return i;
          }
        }
        return -1;
      }
}
