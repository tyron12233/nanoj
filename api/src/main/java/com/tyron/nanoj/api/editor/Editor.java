package com.tyron.nanoj.api.editor;

/**
 * Abstract view of the Code Editor component.
 */
public interface Editor {
    Document getDocument();

    Carets getCaretModel();
    
    /**
     * Scroll the view so the caret is visible.
     */
    void scrollToCaret();

    interface Carets {
        int getOffset();
        void moveToOffset(int offset);
    }
}