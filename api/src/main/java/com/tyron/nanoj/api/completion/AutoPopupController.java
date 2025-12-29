package com.tyron.nanoj.api.completion;

import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.service.Disposable;

/**
 * Listens to editor document changes and automatically requests code completion on trigger characters.
 *
 * This is inspired by IntelliJ's "auto-popup" completion behavior.
 */
public interface AutoPopupController {

    /**
     * Attaches to the editor and calls {@code listener} when completion should be shown.
     *
     * Threading:
     * - Implementations MUST NOT compute completions on the UI thread.
     * - {@code listener} may be invoked on a background thread; UI clients should marshal to their UI thread.
     *
     * @return a disposable handle to detach from the editor.
     */
    Disposable attach(Editor editor, CompletionEventListener listener);
}
