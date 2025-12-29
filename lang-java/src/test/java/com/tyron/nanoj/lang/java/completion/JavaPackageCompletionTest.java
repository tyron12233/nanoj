package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.vfs.FileObject;
import org.junit.jupiter.api.Test;

public class JavaPackageCompletionTest extends BaseJavaCompletionTest {

    @Test
    void importStatement_completesSubpackagesAndTypes() {
        String textWithCaret = """
                package p;
                import java.util.<caret>
                public class Foo {}
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        // type in java.util
        assertHasLookup(r.items, "List");

        // subpackage under java.util
        assertHasLookup(r.items, "concurrent");
    }

    @Test
    void qualifiedReference_completesSubpackagesAndTypes() {
        String textWithCaret = """
                package p;
                public class Foo {
                  void test() {
                    java.util.<caret>
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "List");
        assertHasLookup(r.items, "concurrent");
    }

    @Test
    void importStatement_prefixCompletesNextSegment() {
        String textWithCaret = """
                package p;
                import java.util.co<caret>
                public class Foo {}
                """;

        FileObject file = javaFile("p.Foo", textWithCaret.replace(CARET, ""));
        CompletionResult r = completeAtCaret(file, textWithCaret);

        assertHasLookup(r.items, "concurrent");
    }
}
