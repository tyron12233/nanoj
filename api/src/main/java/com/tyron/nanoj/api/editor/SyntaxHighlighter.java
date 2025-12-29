package com.tyron.nanoj.api.editor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Represents the generic capability to highlight code.
 */
public interface SyntaxHighlighter {
    /**
     * @param content The current text in the editor.
     * @return A future containing a list of token spans (start, end, type).
     */
    CompletableFuture<List<TokenSpan>> highlight(String content);
}
