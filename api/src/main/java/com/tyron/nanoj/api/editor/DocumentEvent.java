package com.tyron.nanoj.api.editor;

/**
 * Represents a single text change in a {@link Document}.
 *
 * Ranges are in document offsets and follow the same convention as {@link Document#replace(int, int, String)}:
 * the replaced range is {@code [startOffset, endOffset)}.
 */
public final class DocumentEvent {

    private final Document document;
    private final int startOffset;
    private final int endOffset;
    private final String newText;

    public DocumentEvent(Document document, int startOffset, int endOffset, String newText) {
        this.document = document;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.newText = newText;
    }

    public Document getDocument() {
        return document;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public String getNewText() {
        return newText;
    }

    public int getOldLength() {
        return Math.max(0, endOffset - startOffset);
    }
}
