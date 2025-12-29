package com.tyron.nanoj.desktop;

import com.tyron.nanoj.api.editor.Document;

import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.util.Objects;

final class SwingTextDocument implements Document {

    private final JTextComponent component;

    SwingTextDocument(JTextComponent component) {
        this.component = Objects.requireNonNull(component, "component");
    }

    @Override
    public String getText() {
        return component.getText();
    }

    @Override
    public int getTextLength() {
        return component.getDocument().getLength();
    }

    @Override
    public void replace(int start, int end, String text) {
        Objects.requireNonNull(text, "text");
        try {
            var doc = component.getDocument();
            doc.remove(start, end - start);
            doc.insertString(start, text, null);
        } catch (BadLocationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void insertString(int offset, String text) {
        Objects.requireNonNull(text, "text");
        try {
            component.getDocument().insertString(offset, text, null);
        } catch (BadLocationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void deleteString(int start, int end) {
        try {
            component.getDocument().remove(start, end - start);
        } catch (BadLocationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public String getText(int start, int length) {
        try {
            return component.getDocument().getText(start, length);
        } catch (BadLocationException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
