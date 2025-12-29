package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.completion.InsertionContext;
import com.tyron.nanoj.api.completion.LookupElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JavaKeywordInsertHandlerTest extends BaseJavaTypingCompletionTest {

    @Test
    void publicKeyword_insertsTrailingSpace() throws Exception {
        String withCaret = """
                package p;
                public class Foo {
                  pub<caret>
                }
                """;

        try (TypingSession s = typingSession("p.Foo", withCaret)) {
            var r = s.complete();
            LookupElement pub = requireLookup(r.items, "public");

            applyCompletionReplacingIdentifierPrefix(s, pub);

            assertEquals(
                    """
                package p;
                public class Foo {
                  public 
                }
                """,
                    s.document.getText(),
                    "document text"
            );

            int expectedCaret = s.document.getText().indexOf("public ") + "public ".length();
            assertEquals(expectedCaret, s.editor.getCaretModel().getOffset(), "caret offset");
        }
    }

    @Test
    void thisKeyword_doesNotInsertTrailingSpace() throws Exception {
        String withCaret = """
                package p;
                public class Foo {
                  void test() {
                    thi<caret>
                  }
                }
                """;

        try (TypingSession s = typingSession("p.Foo", withCaret)) {
            var r = s.complete();
            LookupElement th = requireLookup(r.items, "this");

            applyCompletionReplacingIdentifierPrefix(s, th);

            assertEquals(
                    """
                package p;
                public class Foo {
                  void test() {
                    this
                  }
                }
                """,
                    s.document.getText(),
                    "document text"
            );

            int expectedCaret = s.document.getText().indexOf("this") + "this".length();
            assertEquals(expectedCaret, s.editor.getCaretModel().getOffset(), "caret offset");
        }
    }

    private void applyCompletionReplacingIdentifierPrefix(TypingSession s, LookupElement el) {
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
