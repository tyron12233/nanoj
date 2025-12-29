package com.tyron.nanoj.api.editor;

import com.tyron.nanoj.api.vfs.FileObject;

import java.io.IOException;
import java.util.List;

/**
 * Manages the lifecycle of {@link Editor} instances.
 *
 * An editor edits a {@link Document}. Multiple editors may exist for a single document.
 */
public interface EditorManager {

    /**
     * Opens an editor for the given file (creating or reusing its {@link Document}).
     */
    Editor openEditor(FileObject file) throws IOException;

    /**
     * Creates an editor for an existing document.
     */
    Editor createEditor(Document document);

    /**
     * Releases an editor instance.
     */
    void releaseEditor(Editor editor);

    List<Editor> getEditors(Document document);

    List<Editor> getAllEditors();
}
