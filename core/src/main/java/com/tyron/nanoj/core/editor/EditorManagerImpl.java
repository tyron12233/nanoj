package com.tyron.nanoj.core.editor;

import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.editor.EditorManager;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core implementation of {@link EditorManager}.
 *
 * This is a non-UI editor registry. UI toolkits (Android, Swing, etc.) can wrap or replace these editors.
 */
public final class EditorManagerImpl implements EditorManager {

    public static EditorManagerImpl getInstance(Project project) {
        return ProjectServiceManager.getService(project, EditorManagerImpl.class);
    }

    private final FileDocumentManagerImpl fileDocumentManager;

    private final Object lock = new Object();
    private final Map<Document, List<Editor>> editorsByDocument = new IdentityHashMap<>();
    private final List<Editor> allEditors = new ArrayList<>();

    public EditorManagerImpl(Project project) {
        Objects.requireNonNull(project, "project");
        this.fileDocumentManager = FileDocumentManagerImpl.getInstance(project);
    }

    @Override
    public Editor openEditor(FileObject file) throws IOException {
        Document doc = fileDocumentManager.getDocument(file);
        return createEditor(doc);
    }

    @Override
    public Editor createEditor(Document document) {
        Objects.requireNonNull(document, "document");
        Editor editor = new SimpleEditor(document);

        synchronized (lock) {
            allEditors.add(editor);
            editorsByDocument.computeIfAbsent(document, d -> new ArrayList<>()).add(editor);
        }

        return editor;
    }

    @Override
    public void releaseEditor(Editor editor) {
        Objects.requireNonNull(editor, "editor");

        synchronized (lock) {
            allEditors.remove(editor);
            List<Editor> list = editorsByDocument.get(editor.getDocument());
            if (list != null) {
                list.remove(editor);
                if (list.isEmpty()) {
                    editorsByDocument.remove(editor.getDocument());
                }
            }
        }
    }

    @Override
    public List<Editor> getEditors(Document document) {
        Objects.requireNonNull(document, "document");
        synchronized (lock) {
            List<Editor> list = editorsByDocument.get(document);
            return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    @Override
    public List<Editor> getAllEditors() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(allEditors));
        }
    }
}
