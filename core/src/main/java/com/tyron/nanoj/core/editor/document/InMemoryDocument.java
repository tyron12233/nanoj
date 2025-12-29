package com.tyron.nanoj.core.editor.document;

import com.tyron.nanoj.api.editor.DocumentEvent;
import com.tyron.nanoj.api.editor.DocumentListener;
import com.tyron.nanoj.api.editor.ObservableDocument;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in-memory {@link ObservableDocument} implementation.
 *
 * This is intentionally UI-agnostic and suitable for mobile environments.
 *
 * Thread-safety: all text operations are synchronized on an internal lock.
 */
public final class InMemoryDocument implements ObservableDocument {

    private final Object lock = new Object();
    private final StringBuilder text;
    private final CopyOnWriteArrayList<DocumentListener> listeners = new CopyOnWriteArrayList<>();

    private volatile long modificationStamp;

    public InMemoryDocument(String initialText) {
        this.text = new StringBuilder(initialText != null ? initialText : "");
        this.modificationStamp = 0L;
    }

    @Override
    public String getText() {
        synchronized (lock) {
            return text.toString();
        }
    }

    @Override
    public int getTextLength() {
        synchronized (lock) {
            return text.length();
        }
    }

    @Override
    public void replace(int start, int end, String newText) {
        Objects.requireNonNull(newText, "text");

        DocumentEvent event;
        synchronized (lock) {
            int len = text.length();
            if (start < 0 || end < start || end > len) {
                throw new IndexOutOfBoundsException("replace range [" + start + ", " + end + ") is out of bounds for length=" + len);
            }

            text.replace(start, end, newText);
            modificationStamp++;
            event = new DocumentEvent(this, start, end, newText);
        }

        for (DocumentListener listener : listeners) {
            listener.documentChanged(event);
        }
    }

    @Override
    public void insertString(int offset, String insertedText) {
        replace(offset, offset, insertedText);
    }

    @Override
    public void deleteString(int start, int end) {
        replace(start, end, "");
    }

    @Override
    public String getText(int start, int length) {
        synchronized (lock) {
            int len = text.length();
            if (start < 0 || length < 0 || start + length > len) {
                throw new IndexOutOfBoundsException("getText start=" + start + " length=" + length + " is out of bounds for length=" + len);
            }
            return text.substring(start, start + length);
        }
    }

    @Override
    public void addDocumentListener(DocumentListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeDocumentListener(DocumentListener listener) {
        listeners.remove(listener);
    }

    @Override
    public long getModificationStamp() {
        return modificationStamp;
    }
}
