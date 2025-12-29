package com.tyron.nanoj.api.editor;

/**
 * Optional extension of {@link Document} for implementations that can publish change events.
 */
public interface ObservableDocument extends Document {

    void addDocumentListener(DocumentListener listener);

    void removeDocumentListener(DocumentListener listener);

    /**
     * Monotonically increasing stamp; increments on every change.
     */
    long getModificationStamp();
}
