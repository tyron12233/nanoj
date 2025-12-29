package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.vfs.FileObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaKeywordCompletionTest extends BaseJavaCompletionTest {

    @Test
    void methodBody_suggestsStatementKeywordsOnly() {
        String textWithCaret = """
                package p;
                public class Foo {
                  void test() {
                    ret<caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "return");
        assertNoLookup(r.items, "import");
        assertNoLookup(r.items, "package");
        assertNoLookup(r.items, "class");
    }

    @Test
    void topLevel_suggestsImportKeyword() {
        String textWithCaret = """
                package p;
                imp<caret>
                public class Foo {}
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "import");
        assertNoLookup(r.items, "return");
    }

    @Test
    void classBody_suggestsMemberKeywords() {
        String textWithCaret = """
                package p;
                public class Foo {
                  pub<caret>
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "public");
        assertNoLookup(r.items, "return");
        assertNoLookup(r.items, "import");
    }

    private static void assertNoLookup(java.util.List<com.tyron.nanoj.api.completion.LookupElement> items, String lookupString) {
        boolean present = items.stream().anyMatch(it -> lookupString.equals(it.getLookupString()));
        assertTrue(!present, "Did not expect '" + lookupString + "' in completions: " + debugLookups(items));
    }
}
