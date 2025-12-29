package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.completion.InsertionContext;
import com.tyron.nanoj.api.completion.LookupElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JavaCompletionMethodInsertHandlerTest extends BaseJavaTypingCompletionTest {

    @Test
    void methodWithArgsPlacesCaretInsideParentheses() throws Exception {
        String withCaret = """
                package p;
                public class Foo {
                  void test(String s) {
                    s.subs<caret>
                  }
                }
                """;

        try (TypingSession s = typingSession("p.Foo", withCaret)) {
            var r = s.complete();
            LookupElement substring = requireLookup(r.items, "substring");

            applyCompletion(s, substring);

            assertEquals(
                    """
                package p;
                public class Foo {
                  void test(String s) {
                    s.substring()
                  }
                }
                """,
                    s.document.getText(),
                    "document text"
            );

            int expectedCaret = s.document.getText().indexOf("substring(") + "substring(".length();
            assertEquals(expectedCaret, s.editor.getCaretModel().getOffset(), "caret offset");
        }
    }

    @Test
    void methodWithoutArgsPlacesCaretAfterParentheses() throws Exception {
        String withCaret = """
                package p;
                public class Foo {
                  void test(String s) {
                    s.toSt<caret>
                  }
                }
                """;

        try (TypingSession s = typingSession("p.Foo", withCaret)) {
            var r = s.complete();
            LookupElement toString = requireLookup(r.items, "toString");

            applyCompletion(s, toString);

            assertEquals(
                    """
                package p;
                public class Foo {
                  void test(String s) {
                    s.toString()
                  }
                }
                """,
                    s.document.getText(),
                    "document text"
            );

            int expectedCaret = s.document.getText().indexOf("toString()") + "toString()".length();
            assertEquals(expectedCaret, s.editor.getCaretModel().getOffset(), "caret offset");
        }
    }

    private void applyCompletion(TypingSession s, LookupElement el) {
        String text = s.document.getText();
        int caret = s.editor.getCaretModel().getOffset();

        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }

        InsertionContext ctx = new InsertionContext(project, s.file, s.editor, (char) 0, start, caret);
        el.handleInsert(ctx);
        s.editor.getCaretModel().moveToOffset(ctx.getTailOffset());
    }
}
