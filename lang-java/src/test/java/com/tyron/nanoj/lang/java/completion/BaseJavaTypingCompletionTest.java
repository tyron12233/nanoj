package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.EditorManagerImpl;
import com.tyron.nanoj.core.editor.FileDocumentManagerImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base class for completion tests that simulate a user typing into a {@link Document}.
 */
public abstract class BaseJavaTypingCompletionTest extends BaseJavaCompletionTest {

    protected TypingSession typingSession(String fqcn, String textWithCaret) throws Exception {
        int caretOffset = requireSingleCaret(textWithCaret);
        String text = stripCaret(textWithCaret, caretOffset);

        FileObject file = javaFile(fqcn, text);
        Editor editor = EditorManagerImpl.getInstance(project).openEditor(file);
        Document doc = editor.getDocument();
        editor.getCaretModel().moveToOffset(caretOffset);

        assertFalse(FileDocumentManagerImpl.getInstance(project).isModified(doc), "Document should start unmodified");

        return new TypingSession(file, editor);
    }

    protected final class TypingSession implements AutoCloseable {
        public final FileObject file;
        public final Editor editor;
        public final Document document;

        private TypingSession(FileObject file, Editor editor) {
            this.file = file;
            this.editor = editor;
            this.document = editor.getDocument();
        }

        public CompletionResult complete() {
            return BaseJavaTypingCompletionTest.this.complete(file, document.getText(), editor.getCaretModel().getOffset());
        }

        public void type(String text) {
            int offset = editor.getCaretModel().getOffset();
            document.insertString(offset, text);
            editor.getCaretModel().moveToOffset(offset + text.length());
        }

        public void backspace() {
            int offset = editor.getCaretModel().getOffset();
            if (offset <= 0) return;
            document.deleteString(offset - 1, offset);
            editor.getCaretModel().moveToOffset(offset - 1);
        }

        @Override
        public void close() {
            EditorManagerImpl.getInstance(project).releaseEditor(editor);
        }
    }

    protected static void assertNoLookup(List<LookupElement> items, String lookupString) {
        boolean present = items.stream().anyMatch(it -> lookupString.equals(it.getLookupString()));
        assertTrue(!present, "Did not expect '" + lookupString + "' in completions: " + debugLookups(items));
    }

    private static int requireSingleCaret(String textWithCaret) {
        int caret = textWithCaret.indexOf(CARET);
        assertTrue(caret >= 0, "Missing " + CARET);
        assertTrue(textWithCaret.indexOf(CARET, caret + 1) < 0, "Multiple " + CARET + " markers are not supported");
        return caret;
    }

    private static String stripCaret(String textWithCaret, int caretOffset) {
        return textWithCaret.substring(0, caretOffset) + textWithCaret.substring(caretOffset + CARET.length());
    }
}
