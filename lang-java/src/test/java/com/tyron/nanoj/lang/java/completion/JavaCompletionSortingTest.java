package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.vfs.FileObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaCompletionSortingTest extends BaseJavaCompletionTest {

    @Test
    void identifierCompletion_prefersLocalAndParameter() {
        String textWithCaret = """
                package p;
                public class Foo {
                  int appleField;
                  void appleMethod() {}

                  void test(int appleParam) {
                    int appleLocal = 0;
                    appl<caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "appleLocal");
        assertHasLookup(r.items, "appleParam");
        assertHasLookup(r.items, "appleField");
        assertHasLookup(r.items, "appleMethod");

        assertBefore(r.items, "appleLocal", "appleField");
        assertBefore(r.items, "appleLocal", "appleMethod");
        assertBefore(r.items, "appleParam", "appleField");
        assertBefore(r.items, "appleParam", "appleMethod");
    }

    private static void assertBefore(List<com.tyron.nanoj.api.completion.LookupElement> items, String a, String b) {
        int ia = indexOf(items, a);
        int ib = indexOf(items, b);
        assertTrue(ia >= 0 && ib >= 0, "Missing lookups: a=" + a + " b=" + b + " got=" + debugLookups(items));
        assertTrue(ia < ib, "Expected '" + a + "' before '" + b + "' but got=" + debugLookups(items));
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
