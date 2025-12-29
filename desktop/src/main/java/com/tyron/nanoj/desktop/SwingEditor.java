package com.tyron.nanoj.desktop;

import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.Editor;

import javax.swing.text.JTextComponent;
import java.util.Objects;

final class SwingEditor implements Editor {

    private final JTextComponent component;
    private final Document document;

    SwingEditor(JTextComponent component) {
        this.component = Objects.requireNonNull(component, "component");
        this.document = new SwingTextDocument(component);
    }

    @Override
    public Document getDocument() {
        return document;
    }

    @Override
    public Carets getCaretModel() {
        return new Carets() {
            @Override
            public int getOffset() {
                return component.getCaretPosition();
            }

            @Override
            public void moveToOffset(int offset) {
                component.setCaretPosition(Math.max(0, Math.min(offset, component.getDocument().getLength())));
            }
        };
    }

    @Override
    public void scrollToCaret() {
        // RSyntaxTextArea handles scrolling itself.
    }
}
