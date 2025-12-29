package com.tyron.nanoj.desktop;

import com.tyron.nanoj.api.completion.InsertionContext;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementPresentation;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.EditorSession;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;

final class NanojCompletionPopup {

    private final Project project;
    private final FileObject file;
    private final JTextComponent editor;
    private final EditorSession session;

    private final JComponent popupContent;
    private Popup popup;
    private final DefaultListModel<LookupElement> model = new DefaultListModel<>();
    private final JList<LookupElement> list = new JList<>(model);

    private final Timer updateTimer;

    NanojCompletionPopup(Project project, FileObject file, JTextComponent editor) {
        this.project = Objects.requireNonNull(project, "project");
        this.file = Objects.requireNonNull(file, "file");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.session = new EditorSession(project, file);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setFocusable(false);
        list.setRequestFocusEnabled(false);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(520, 260));

        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        Color border = UIManager.getColor("Component.borderColor");
        if (border == null) {
            border = UIManager.getColor("controlDkShadow");
        }
        if (border == null) {
            border = Color.DARK_GRAY;
        }
        content.setBorder(BorderFactory.createLineBorder(border));
        this.popupContent = content;

        this.updateTimer = new Timer(120, e -> {
            if (shouldAutoPopup()) {
                showOrUpdate();
            } else if (isVisible()) {
                hide();
            }
        });
        this.updateTimer.setRepeats(false);

        // IntelliJ-like: keep popup open and continuously update as user types.
        editor.getDocument().addDocumentListener(new DocumentListener() {
            private void schedule() {
                updateTimer.restart();
            }

            @Override public void insertUpdate(DocumentEvent e) { schedule(); }
            @Override public void removeUpdate(DocumentEvent e) { schedule(); }
            @Override public void changedUpdate(DocumentEvent e) { schedule(); }
        });

        // Mouse insert
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    insertSelected();
                }
            }
        });

        installKeybindings();
    }

    void showOrUpdate() {
        List<LookupElement> items = getItems();
        if (items.isEmpty()) {
            hide();
            return;
        }

        model.clear();
        for (LookupElement el : items) {
            model.addElement(el);
        }
        list.setSelectedIndex(0);

        if (!isVisible()) {
            showAtCaret();
        }
    }

    void hide() {
        Popup p = popup;
        popup = null;
        if (p != null) {
            p.hide();
        }
    }

    private void showAtCaret() {
        int caret = editor.getCaretPosition();
        try {
            Rectangle r = editor.modelToView(caret);
            int x = r != null ? r.x : 0;
            int y = r != null ? (r.y + r.height) : editor.getHeight();
            showAtEditorPoint(x, y);
        } catch (BadLocationException e) {
            showAtEditorPoint(0, editor.getHeight());
        }
    }

    private void showAtEditorPoint(int x, int y) {
        hide();

        Point p = new Point(x, y);
        SwingUtilities.convertPointToScreen(p, editor);
        popup = PopupFactory.getSharedInstance().getPopup(editor, popupContent, p.x, p.y);
        popup.show();

        // Ensure editor remains the focus owner so typing continues.
        SwingUtilities.invokeLater(editor::requestFocusInWindow);
    }

    private boolean isVisible() {
        return popup != null;
    }

    private List<LookupElement> getItems() {
        String text = editor.getText();
        int offset = editor.getCaretPosition();

        final java.util.ArrayList<LookupElement> items = new java.util.ArrayList<>();
        session.onCompletionRequest(text, offset, items::addAll);

        String prefix = currentIdentifierPrefix(text, offset);
        if (prefix.isEmpty()) {
            return items;
        }

        items.removeIf(el -> {
            String s = el.getLookupString();
            return s == null || !s.startsWith(prefix);
        });

        return items;
    }

    private void insertSelected() {
        LookupElement el = list.getSelectedValue();
        if (el == null) {
            hide();
            return;
        }

        String text = editor.getText();
        int caret = editor.getCaretPosition();

        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }

        SwingEditor nanojEditor = new SwingEditor(editor);
        InsertionContext ctx = new InsertionContext(project, file, nanojEditor, (char) 0, start, caret);

        el.handleInsert(ctx);

        nanojEditor.getCaretModel().moveToOffset(ctx.getTailOffset());

        hide();

        Runnable later = ctx.getLaterRunnable();
        if (later != null) {
            later.run();
        }
    }

    private void installKeybindings() {
        InputMap im = editor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = editor.getActionMap();

        // NOTE: no Ctrl+Space trigger; this is auto-popup only.

        String escapeKey = "nanoj.complete.hide";
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), escapeKey);
        am.put("nanoj.complete.hide", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hide();
            }
        });

        // Navigation/insert while popup is open (but don't break normal editor behavior when popup is closed).
        installConditionalKey(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "nanoj.complete.down", DefaultEditorKit.downAction,
                () -> {
                    int i = Math.min(model.size() - 1, list.getSelectedIndex() + 1);
                    if (i >= 0) list.setSelectedIndex(i);
                });

        installConditionalKey(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "nanoj.complete.up", DefaultEditorKit.upAction,
                () -> {
                    int i = Math.max(0, list.getSelectedIndex() - 1);
                    list.setSelectedIndex(i);
                });

        installConditionalKey(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nanoj.complete.insert", DefaultEditorKit.insertBreakAction,
                this::insertSelected);

        // Tab mapping varies; capture current mapping and delegate when popup not visible.
        KeyStroke tab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        Object existingTabKey = im.get(tab);
        Action existingTabAction = existingTabKey != null ? am.get(existingTabKey) : null;
        im.put(tab, "nanoj.complete.tab");
        am.put("nanoj.complete.tab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isVisible()) {
                    insertSelected();
                    return;
                }
                if (existingTabAction != null) {
                    existingTabAction.actionPerformed(e);
                }
            }
        });
    }

    private void installConditionalKey(InputMap im, ActionMap am, KeyStroke stroke, String actionKey,
                                      String defaultEditorKitActionKey, Runnable whenPopupVisible) {
        Action fallback = am.get(defaultEditorKitActionKey);
        im.put(stroke, actionKey);
        am.put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isVisible()) {
                    whenPopupVisible.run();
                    return;
                }
                if (fallback != null) {
                    fallback.actionPerformed(e);
                }
            }
        });
    }

    private boolean shouldAutoPopup() {
        String text = editor.getText();
        int offset = editor.getCaretPosition();

        if (offset <= 0) {
            return false;
        }

        char prev = text.charAt(offset - 1);
        if (prev == '.') {
            return true;
        }

        if (Character.isJavaIdentifierPart(prev)) {
            // Only auto-popup when we're in/after an identifier-like sequence.
            return true;
        }

        return false;
    }

    private static String currentIdentifierPrefix(String text, int offset) {
        if (text == null || offset <= 0) {
            return "";
        }

        int end = Math.min(offset, text.length());
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    private static final class Renderer extends JPanel implements ListCellRenderer<LookupElement> {

        private final JLabel left = new JLabel();
        private final JLabel right = new JLabel();

        private Renderer() {
            super(new BorderLayout(8, 0));
            left.setOpaque(false);
            right.setOpaque(false);
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            add(left, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends LookupElement> list, LookupElement value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            LookupElementPresentation p = new LookupElementPresentation();
            value.renderElement(p);

            String main = p.getItemText() != null ? p.getItemText() : value.getLookupString();
            String tail = p.getTailText() != null ? p.getTailText() : "";
            left.setText(main + tail);

            String type = p.getTypeText();
            right.setText(type != null ? type : "");

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                left.setForeground(list.getSelectionForeground());
                right.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                left.setForeground(list.getForeground());
                Color disabled = UIManager.getColor("Label.disabledForeground");
                right.setForeground(disabled != null ? disabled : Color.GRAY);
            }

            left.setFont(list.getFont());
            right.setFont(list.getFont());
            return this;
        }
    }
}
