package com.tyron.nanoj.core.editor;

import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.vfs.FileObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * A {@link FileObject} wrapper that exposes the current in-memory {@link Document} content via read APIs.
 *
 * Writes are delegated to the underlying file object; persistence is controlled by {@link FileDocumentManagerImpl}.
 */
public final class InMemoryFileObjectView implements FileObject {

    private final FileObject delegate;
    private final Document document;

    public InMemoryFileObjectView(FileObject delegate, Document document) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.document = Objects.requireNonNull(document, "document");
    }

    public FileObject getDelegate() {
        return delegate;
    }

    public Document getDocument() {
        return document;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getExtension() {
        return delegate.getExtension();
    }

    @Override
    public String getPath() {
        return delegate.getPath();
    }

    @Override
    public URI toUri() {
        return delegate.toUri();
    }

    @Override
    public FileObject getParent() {
        return delegate.getParent();
    }

    @Override
    public List<FileObject> getChildren() {
        return delegate.getChildren();
    }

    @Override
    public FileObject getChild(String name) {
        return delegate.getChild(name);
    }

    @Override
    public boolean exists() {
        return delegate.exists();
    }

    @Override
    public boolean isFolder() {
        return delegate.isFolder();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public long lastModified() {
        // This view is in-memory, but we keep disk timestamp semantics.
        return delegate.lastModified();
    }

    @Override
    public long getLength() {
        return document.getText().getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(document.getText().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return delegate.getOutputStream();
    }

    @Override
    public FileObject createFile(String name) throws IOException {
        return delegate.createFile(name);
    }

    @Override
    public FileObject createFolder(String name) throws IOException {
        return delegate.createFolder(name);
    }

    @Override
    public void delete() throws IOException {
        delegate.delete();
    }

    @Override
    public void rename(String newName) throws IOException {
        delegate.rename(newName);
    }

    @Override
    public void refresh() {
        delegate.refresh();
    }
}
