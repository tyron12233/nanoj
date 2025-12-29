package com.tyron.nanoj.api.completion;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.Editor;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides context to {@link InsertHandler}s about the environment where the completion happened.
 * <p>
 * This class is mutable. Handlers can modify the offsets or schedule tasks to run after insertion.
 * </p>
 */
public class InsertionContext {

    private final Project project;
    private final FileObject file;
    private final Editor editor;
    private final char completionChar; // The char that triggered completion (e.g. '.' or TAB or Enter)
    
    // Mutable state
    private int startOffset;
    private int tailOffset; // Where the caret ends up
    private int selectionEndOffset; // The end of the inserted string
    private boolean addCompletionChar; // If true, the char that triggered popup is inserted

    private Runnable laterRunnable; // Task to run after this transaction finishes

    // Optional hook for environments that require explicit commit/persist.
    private Runnable commitDocumentRunnable;

    // Shared map to pass data between LookupElement and InsertHandler (e.g. "shouldImport")
    private final Map<Object, Object> sharedContext = new HashMap<>();

    public InsertionContext(Project project, 
                            FileObject file, 
                            Editor editor, 
                            char completionChar,
                            int startOffset, 
                            int tailOffset) {
        this.project = project;
        this.file = file;
        this.editor = editor;
        this.completionChar = completionChar;
        this.startOffset = startOffset;
        this.tailOffset = tailOffset;
        this.selectionEndOffset = tailOffset;
        this.addCompletionChar = true;
    }

    // =============================
    //      Getters / Context
    // =============================

    public Project getProject() {
        return project;
    }

    public FileObject getFile() {
        return file;
    }

    public Editor getEditor() {
        return editor;
    }

    public Document getDocument() {
        return editor.getDocument();
    }

    /**
     * @return The character that forced the completion (e.g. \t, \n, ., ( ). 
     * Returns 0 if explicitly selected from list via touch.
     */
    public char getCompletionChar() {
        return completionChar;
    }

    public Map<Object, Object> getSharedContext() {
        return sharedContext;
    }

    // =============================
    //      Offset Management
    // =============================

    public int getStartOffset() {
        return startOffset;
    }

    public int getTailOffset() {
        return tailOffset;
    }
    
    /**
     * @return The offset at the end of the inserted identifier. 
     * Note: tailOffset might be different if the handler moved the caret inside parenthesis.
     */
    public int getSelectionEndOffset() {
        return selectionEndOffset;
    }

    /**
     * If you insert extra characters (like parenthesis), update the tail offset
     * so the IDE knows where the caret is relative to the new text.
     */
    public void setTailOffset(int offset) {
        this.tailOffset = offset;
    }

    public void setSelectionEndOffset(int offset) {
        this.selectionEndOffset = offset;
    }

    // =============================
    //      Behavior Control
    // =============================

    public void setAddCompletionChar(boolean add) {
        this.addCompletionChar = add;
    }

    public boolean shouldAddCompletionChar() {
        return addCompletionChar;
    }

    /**
     * Schedule a task to run after the completion transaction is fully closed.
     * Useful for triggering a new completion popup (e.g. after typing "new ").
     */
    public void setLaterRunnable(Runnable runnable) {
        this.laterRunnable = runnable;
    }

    public Runnable getLaterRunnable() {
        return laterRunnable;
    }

    /**
     * Installs an optional commit hook for {@link #commitDocument()}.
     */
    public void setCommitDocumentRunnable(Runnable runnable) {
        this.commitDocumentRunnable = runnable;
    }
    
    /**
     * Helper to simulate "committing" changes if your document model relies on it.
     * In a simple in-memory document, this might be a no-op.
     */
    public void commitDocument() {
        if (commitDocumentRunnable != null) {
            commitDocumentRunnable.run();
        }
    }
}