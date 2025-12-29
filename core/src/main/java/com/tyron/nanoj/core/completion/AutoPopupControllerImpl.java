package com.tyron.nanoj.core.completion;

import com.tyron.nanoj.api.completion.*;
import com.tyron.nanoj.api.concurrent.TaskPriority;
import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.DocumentEvent;
import com.tyron.nanoj.api.editor.DocumentListener;
import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.editor.ObservableDocument;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.FileDocumentManagerImpl;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.util.List;
import java.util.Objects;

/**
 * Core implementation of {@link AutoPopupController}.
 *
 * Attaches a {@link DocumentListener} to the editor document and triggers completion
 * when certain characters are inserted.
 */
public final class AutoPopupControllerImpl implements AutoPopupController {

    private static final String LANE = "completion/autopopup";

    public static AutoPopupControllerImpl getInstance(Project project) {
        return ProjectServiceManager.getService(project, AutoPopupControllerImpl.class);
    }

    private final Project project;
    private final CodeCompletionServiceImpl completionService;
    private final FileDocumentManagerImpl fileDocumentManager;
    private final TaskScheduler scheduler;

    public AutoPopupControllerImpl(Project project) {
        this.project = Objects.requireNonNull(project, "project");
        this.completionService = CodeCompletionServiceImpl.getInstance(project);
        this.fileDocumentManager = FileDocumentManagerImpl.getInstance(project);
        this.scheduler = ProjectServiceManager.getService(project, TaskScheduler.class);
    }

    @Override
    public Disposable attach(Editor editor, CompletionEventListener listener) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(listener, "listener");

        Document doc = editor.getDocument();
        if (!(doc instanceof ObservableDocument observable)) {
            throw new IllegalArgumentException("Editor document must be ObservableDocument to attach AutoPopupController.");
        }

        FileObject file = fileDocumentManager.getFile(doc);
        if (file == null) {
            throw new IllegalStateException("No FileObject associated with document. Open the editor via EditorManager/FileDocumentManager.");
        }

        DocumentListener docListener = event -> {
            char trigger = singleCharTrigger(event.getNewText());
            if (trigger == 0) return;

            // NOTE: the editor caret may not be updated yet when the document listener fires.
            // Use the post-insert offset based on the document event instead.
            int offset = offsetAfterEvent(event);
            String text = doc.getText();

            if (!isAutoPopupTrigger(trigger, text, offset)) return;

            scheduler.submitLatest(LANE, editor, TaskPriority.USER, ctx -> {
                try {
                    ctx.cancellation().throwIfCancelled();
                    return completionService.getCompletions(file, text, offset);
                } catch (Throwable ignored) {
                    return List.<LookupElement>of();
                }
            }).thenAccept(items -> {
                if (items == null || items.isEmpty()) {
                    return;
                }
                listener.onCompletion(new CompletionEvent(editor, file, offset, trigger, items));
            });
        };

        observable.addDocumentListener(docListener);

        return () -> {
            observable.removeDocumentListener(docListener);
            scheduler.cancel(LANE, editor);
        };
    }

    private static boolean isAutoPopupTrigger(char ch) {
        return ch == '.' || ch == ':' || ch == '(';
    }

    private static boolean isAutoPopupTrigger(char ch, String text, int caretOffset) {
        if (isAutoPopupTrigger(ch)) return true;
        if (ch == ' ') return isSpaceAfterNew(text, caretOffset);
        return false;
    }

    private static boolean isSpaceAfterNew(String text, int caretOffset) {
        if (text == null) return false;
        // We expect the document to end with "new " right before caret.
        if (caretOffset < 4) return false;

        int start = caretOffset - 4;
        if (!text.regionMatches(start, "new ", 0, 4)) {
            return false;
        }

        // Word boundary check: avoid triggering for "renew ", "knew ", etc.
        int before = start - 1;
        if (before < 0) return true;
        return !Character.isJavaIdentifierPart(text.charAt(before));
    }

    private static char singleCharTrigger(String inserted) {
        if (inserted == null) return 0;
        if (inserted.length() != 1) return 0;
        return inserted.charAt(0);
    }

    private static int offsetAfterEvent(DocumentEvent event) {
        if (event == null) return 0;
        String newText = event.getNewText();
        int len = newText != null ? newText.length() : 0;
        return Math.max(0, event.getStartOffset() + len);
    }
}
