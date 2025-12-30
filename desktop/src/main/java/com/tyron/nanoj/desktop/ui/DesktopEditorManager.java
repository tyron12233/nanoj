package com.tyron.nanoj.desktop.ui;

import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.editor.EditorManager;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.EditorManagerImpl;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Desktop EditorManager that also owns the main Swing IDE frame.
 */
public final class DesktopEditorManager implements EditorManager {

    private final Project project;
    private final EditorManagerImpl delegate;
    private final DesktopIdeFrame frame;

    public DesktopEditorManager(Project project) {
        this.project = Objects.requireNonNull(project, "project");
        this.delegate = new EditorManagerImpl(project);
        this.frame = new DesktopIdeFrame(project);
    }

    public DesktopIdeFrame getFrame() {
        return frame;
    }

    @Override
    public Editor openEditor(FileObject file) throws IOException {
        Editor editor = delegate.openEditor(file);
        frame.openInitialFile(file);
        return editor;
    }

    @Override
    public Editor createEditor(Document document) {
        return delegate.createEditor(document);
    }

    @Override
    public void releaseEditor(Editor editor) {
        delegate.releaseEditor(editor);
    }

    @Override
    public List<Editor> getEditors(Document document) {
        return delegate.getEditors(document);
    }

    @Override
    public List<Editor> getAllEditors() {
        return delegate.getAllEditors();
    }
}
