package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.completion.InsertionContext;
import com.tyron.nanoj.api.completion.LookupElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JavaNewClassInsertHandlerTest extends BaseJavaTypingCompletionTest {

    @Test
    void newClassInsertion_addsImportAndPlacesCaretInsideWhenCtorHasArgs() throws Exception {
        String withCaret = """
                package p;
                import java.io.InputStream;
                public class Foo {
                  InputStream in = new F<caret>
                }
                """;

        try (TypingSession s = typingSession("p.Foo", withCaret)) {
            var r = s.complete();
            LookupElement fileInputStream = requireLookup(r.items, "FileInputStream");

            applyCompletion(s, fileInputStream);

            assertEquals(
                    """
                package p;
                import java.io.InputStream;
                import java.io.FileInputStream;

                public class Foo {
                  InputStream in = new FileInputStream()
                }
                """,
                    s.document.getText(),
                    "document text"
            );

            int expectedCaret = s.document.getText().indexOf("new FileInputStream(") + "new FileInputStream(".length();
            assertEquals(expectedCaret, s.editor.getCaretModel().getOffset(), "caret offset");
        }
    }

        @Test
        void newGenericClassInsertion_insertsDiamondAndImport() throws Exception {
                String withCaret = """
                                package p;
                                import java.util.List;
                                public class Foo {
                                    List<String> xs = new A<caret>
                                }
                                """;

                try (TypingSession s = typingSession("p.Foo", withCaret)) {
                        var r = s.complete();
                        LookupElement arrayList = requireLookup(r.items, "ArrayList");

                        applyCompletion(s, arrayList);

                        assertEquals(
                                        """
                                package p;
                                import java.util.List;
                                import java.util.ArrayList;

                                public class Foo {
                                    List<String> xs = new ArrayList<>()
                                }
                                """,
                                        s.document.getText(),
                                        "document text"
                        );

                        int expectedCaret = s.document.getText().indexOf("new ArrayList<>()") + "new ArrayList<>()".length();
                        assertEquals(expectedCaret, s.editor.getCaretModel().getOffset(), "caret offset");
                }
        }

    private void applyCompletion(TypingSession s, LookupElement el) {
        int caret = s.editor.getCaretModel().getOffset();
        String text = s.document.getText();

        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }

        int end = caret;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }

        if (start < end) {
            s.document.deleteString(start, end);
            s.editor.getCaretModel().moveToOffset(start);
            caret = start;
        }

        InsertionContext ctx = new InsertionContext(project, s.file, s.editor, (char) 0, start, caret);
        el.handleInsert(ctx);
        s.editor.getCaretModel().moveToOffset(ctx.getTailOffset());
    }

        @Test
        void newClassInsertion_doesNotDuplicateExistingTypeArgsOrParens() throws Exception {
                String withCaret = """
                                package p;
                                import java.util.List;
                                public class Foo {
                                    List<String> xs = new ArrayL<caret><>();
                                }
                                """;

                try (TypingSession s = typingSession("p.Foo", withCaret)) {
                        var r = s.complete();
                        LookupElement arrayList = requireLookup(r.items, "ArrayList");

                        applyCompletion(s, arrayList);

                        assertEquals(
                                        """
                                package p;
                                import java.util.List;
                                import java.util.ArrayList;

                                public class Foo {
                                    List<String> xs = new ArrayList<>();
                                }
                                """,
                                        s.document.getText(),
                                        "document text"
                        );

                        int expectedCaret = s.document.getText().indexOf("new ArrayList<>()") + "new ArrayList<>()".length();
                        assertEquals(expectedCaret, s.editor.getCaretModel().getOffset(), "caret offset");
                }
        }
}
