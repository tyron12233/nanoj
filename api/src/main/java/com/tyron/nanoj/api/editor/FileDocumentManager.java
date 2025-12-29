package com.tyron.nanoj.api.editor;

import com.tyron.nanoj.api.vfs.FileObject;

import java.io.IOException;

/**
 * Manages mapping between {@link FileObject} and in-memory {@link Document}.
 *
 * Documents are edited in-memory and are only persisted to disk when explicitly committed.
 */
public interface FileDocumentManager {

    /**
     * Returns a cached in-memory document for the given file, loading it if necessary.
     */
    Document getDocument(FileObject file) throws IOException;

    /**
     * Returns the file associated with a document, or null if unknown.
     */
    FileObject getFile(Document document);

    /**
     * @return true if the document has uncommitted in-memory changes.
     */
    boolean isModified(Document document);

    /**
     * Writes the document content to disk.
     */
    void commitDocument(Document document) throws IOException;

    /**
     * Writes all modified documents to disk.
     */
    void commitAllDocuments() throws IOException;

    /**
     * Returns a {@link FileObject} view whose read operations reflect the current in-memory document content,
     * if such a document exists; otherwise returns the original file.
     */
    FileObject getInMemoryView(FileObject file);
}
