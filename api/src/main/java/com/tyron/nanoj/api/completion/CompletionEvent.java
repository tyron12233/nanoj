package com.tyron.nanoj.api.completion;

import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.vfs.FileObject;

import java.util.List;

/**
 * Completion result produced by the IDE completion infrastructure.
 */
public final class CompletionEvent {

    private final Editor editor;
    private final FileObject file;
    private final int offset;
    private final char triggerChar;
    private final List<LookupElement> items;

    public CompletionEvent(Editor editor, FileObject file, int offset, char triggerChar, List<LookupElement> items) {
        this.editor = editor;
        this.file = file;
        this.offset = offset;
        this.triggerChar = triggerChar;
        this.items = items;
    }

    public Editor getEditor() {
        return editor;
    }

    public FileObject getFile() {
        return file;
    }

    public int getOffset() {
        return offset;
    }

    public char getTriggerChar() {
        return triggerChar;
    }

    public List<LookupElement> getItems() {
        return items;
    }
}
