package com.tyron.nanoj.api.language;

import com.tyron.nanoj.api.completion.CompletionProvider;
import com.tyron.nanoj.api.diagnostics.DiagnosticsProvider;
import com.tyron.nanoj.api.editor.SyntaxHighlighter;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;

/**
 * Factory interface for language services.
 */
public interface LanguageSupport {
    
    /**
     * @return true if this support handles the given file (e.g. endsWith(".java"))
     */
    boolean canHandle(FileObject file);

    /**
     * Creates a highlighter specific to this file context.
     */
    SyntaxHighlighter createHighlighter(Project project, FileObject file);

    /**
     * Creates a completer specific to this file context.
     */
    CompletionProvider createCompletionProvider(Project project, FileObject file);

    /**
     * Creates a diagnostics provider specific to this file context.
     *
     * Returning {@code null} means diagnostics are not supported for this language.
     */
    default DiagnosticsProvider createDiagnosticsProvider(Project project, FileObject file) {
        return null;
    }
}