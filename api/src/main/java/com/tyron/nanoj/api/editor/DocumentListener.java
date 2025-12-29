package com.tyron.nanoj.api.editor;

/**
 * Listener for {@link Document} changes.
 */
public interface DocumentListener {

    /**
     * Fired after the document has been modified.
     */
    void documentChanged(DocumentEvent event);
}
