package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.vfs.FileObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaCompletionEndToEndTest extends BaseJavaCompletionTest {

    @Test
    void includesTopLevelClassNamesFromSameFile() {
        FileObject file = javaFile("p.Top",
                """
                        package p;
                        public class Top {
                          void test() {
                            To<caret> x;
                          }
                        }
                        class HelperType {}
                        """);

        CompletionResult r = completeAtCaret(file,
                """
                        package p;
                        public class Top {
                          void test() {
                            To<caret> x;
                          }
                        }
                        class HelperType {}
                        """);

        List<LookupElement> items = r.items;
        assertFalse(items.isEmpty());

        // ScopeCompletions always adds top-level class names as plain strings.
        assertHasLookup(items, "Top");
        assertHasLookup(items, "HelperType");
    }

    @Test
    void returnsSomeItemsInMethodScope() {
        FileObject file = javaFile("p.Foo",
                """
                        package p;
                        public class Foo {
                          int field;
                          void method() {}
                          void test() {
                            this.fi<caret> = 0;
                          }
                        }
                        """);

        CompletionResult r = completeAtCaret(file,
                """
                        package p;
                        public class Foo {
                          int field;
                          void method() {}
                          void test() {
                            this.fi<caret> = 0;
                          }
                        }
                        """);

        List<LookupElement> items = r.items;
        assertFalse(items.isEmpty());

        boolean hasField = items.stream().anyMatch(it -> "field".equals(it.getLookupString()));
        assertTrue(hasField, "Expected 'field' in completions: " + debugLookups(items));
    }

    @Test
    void classNamesGenericTest() {
        FileObject file = javaFile("p.Foo",
                """
                        package p;
                        public class Foo {
                          void test() {
                            Li<caret> x;
                          }
                        }
                        """);

        CompletionResult r = completeAtCaret(file,
                """
                        package p;
                        public class Foo {
                          void test() {
                            A<caret> x;
                          }
                        }
                        """);

        var items = r.items;
        assertFalse(items.isEmpty());

        System.out.println(items);
    }
}
