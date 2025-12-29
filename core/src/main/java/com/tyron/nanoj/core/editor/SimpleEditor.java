package com.tyron.nanoj.core.editor;

import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.Editor;

import java.util.Objects;

/**
 * Minimal {@link Editor} implementation.
 *
 * This does not render anything; it only provides caret state and the document reference.
 */
public final class SimpleEditor implements Editor {

    private final Document document;
    private final SimpleCarets carets = new SimpleCarets();

    public SimpleEditor(Document document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    @Override
    public Document getDocument() {
        return document;
    }

    @Override
    public Carets getCaretModel() {
        return carets;
    }

    @Override
    public void scrollToCaret() {
        // No-op (UI specific).
    }

    private static final class SimpleCarets implements Carets {
        private volatile int offset;

        @Override
        public int getOffset() {
            return offset;
        }

        @Override
        public void moveToOffset(int offset) {
            if (offset < 0) {
                throw new IllegalArgumentException("offset < 0: " + offset);
            }
            this.offset = offset;
        }
    }
}
