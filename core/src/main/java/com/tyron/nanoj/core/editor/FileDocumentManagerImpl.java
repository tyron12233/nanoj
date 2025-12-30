package com.tyron.nanoj.core.editor;

import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.DocumentListener;
import com.tyron.nanoj.api.editor.FileDocumentManager;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.document.InMemoryDocument;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.api.vfs.VirtualFileManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core implementation of {@link FileDocumentManager}.
 *
 * Responsibilities:
 * - Cache file text as {@link Document} instances
 * - Track modified state
 * - Commit changes to disk only when requested
 * - Provide an in-memory {@link FileObject} view so other subsystems can read unsaved content
 */
public final class FileDocumentManagerImpl implements FileDocumentManager {

    public static FileDocumentManagerImpl getInstance(Project project) {
        return ProjectServiceManager.getService(project, FileDocumentManagerImpl.class);
    }

    private record Entry(FileObject file, InMemoryDocument document, InMemoryFileObjectView inMemoryView) {
    }

    private final Map<String, Entry> byPath = new ConcurrentHashMap<>();
    private final Map<Document, Entry> byDocument = new IdentityHashMap<>();
    private final Map<Document, Boolean> modified = new IdentityHashMap<>();

    private final Object lock = new Object();

    public FileDocumentManagerImpl(Project project) {
        Objects.requireNonNull(project, "project");
    }

    @Override
    public Document getDocument(FileObject file) throws IOException {
        Objects.requireNonNull(file, "file");
        String path = file.getPath();

        Entry existing = byPath.get(path);
        if (existing != null) {
            return existing.document;
        }

        // Load from disk/VFS once.
        String initialText = file.getText();
        InMemoryDocument doc = new InMemoryDocument(initialText);
        InMemoryFileObjectView view = new InMemoryFileObjectView(file, doc);
        Entry entry = new Entry(file, doc, view);

        // Track modifications.
        DocumentListener listener = event -> {
            synchronized (lock) {
                modified.put(doc, Boolean.TRUE);
            }
        };
        doc.addDocumentListener(listener);

        synchronized (lock) {
            // Double-check under lock in case of concurrent open.
            Entry raced = byPath.get(path);
            if (raced != null) {
                return raced.document;
            }
            byPath.put(path, entry);
            byDocument.put(doc, entry);
            modified.put(doc, Boolean.FALSE);
        }

        return doc;
    }

    @Override
    public FileObject getFile(Document document) {
        Objects.requireNonNull(document, "document");
        synchronized (lock) {
            Entry entry = byDocument.get(document);
            return entry != null ? entry.file : null;
        }
    }

    @Override
    public boolean isModified(Document document) {
        Objects.requireNonNull(document, "document");
        synchronized (lock) {
            return Boolean.TRUE.equals(modified.get(document));
        }
    }

    @Override
    public void commitDocument(Document document) throws IOException {
        Objects.requireNonNull(document, "document");

        Entry entry;
        synchronized (lock) {
            entry = byDocument.get(document);
        }
        if (entry == null) {
            return;
        }

        if (!isModified(document)) {
            return;
        }

        if (entry.file.isReadOnly()) {
            throw new IOException("File is read-only: " + entry.file.getPath());
        }

        // Persist using UTF-8 to match FileObject#getText default.
        try (OutputStream out = entry.file.getOutputStream()) {
            out.write(document.getText().getBytes(StandardCharsets.UTF_8));
        }

        synchronized (lock) {
            modified.put(document, Boolean.FALSE);
        }

        // Notify VFS-based subsystems (indexing, etc.).
        VirtualFileManager.getInstance().fireFileChanged(entry.file);
    }

    @Override
    public void commitAllDocuments() throws IOException {
        ArrayList<Document> docs;
        synchronized (lock) {
            docs = new ArrayList<>(byDocument.keySet());
        }

        IOException first = null;
        for (Document doc : docs) {
            if (!isModified(doc)) continue;
            try {
                commitDocument(doc);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }

        if (first != null) {
            throw first;
        }
    }

    @Override
    public FileObject getInMemoryView(FileObject file) {
        Objects.requireNonNull(file, "file");
        Entry entry = byPath.get(file.getPath());
        return entry != null ? entry.inMemoryView : file;
    }
}
