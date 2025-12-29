package com.tyron.nanoj.api.editor;

/**
 * Abstract view of the text content.
 */
public interface Document {
    String getText();
    int getTextLength();

    /**
     * Replaces text in the range [start, end).
     */
    void replace(int start, int end, String text);
    
    void insertString(int offset, String text);
    
    void deleteString(int start, int end);
    
    /**
     * @return The text in the given range.
     */
    String getText(int start, int length);
}