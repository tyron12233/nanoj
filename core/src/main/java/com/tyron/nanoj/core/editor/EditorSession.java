package com.tyron.nanoj.core.editor;

import com.tyron.nanoj.api.completion.*;
import com.tyron.nanoj.api.dumb.DumbAware;
import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.editor.SyntaxHighlighter;
import com.tyron.nanoj.api.editor.TokenSpan;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.completion.CompletionResultSetImpl;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.util.List;
import java.util.function.Consumer;

public class EditorSession implements Disposable {
    
    private final Project project;
    private final FileObject file;

    private final SyntaxHighlighter highlighter;
    private final CompletionProvider completer;
    
    public EditorSession(Project project, FileObject file) {
        this.project = project;
        this.file = file;

        List<LanguageSupport> languages = ProjectServiceManager.getExtensions(project, LanguageSupport.class);
        LanguageSupport handler = languages.stream()
                .filter(lang -> lang.canHandle(file))
                .findFirst()
                .orElseThrow();

        if (handler.canHandle(file)) {
            this.highlighter = handler.createHighlighter(project, file);
            this.completer = handler.createCompletionProvider(project, file);
        } else {
            this.highlighter = null; // Plain text
            this.completer = null;
        }
    }

    /**
     * Called by UI when text changes.
     */
    public void onTextChanged(String newText, Consumer<List<TokenSpan>> uiCallback) {
        if (highlighter != null) {
            highlighter.highlight(newText)
                .thenAccept(spans -> {
                    uiCallback.accept(spans);
                });
        }
    }

    /**
     * Called by UI when user requests completion.
     */
    public void onCompletionRequest(String text, int pos, Consumer<List<LookupElement>> uiCallback) {
        if (completer != null) {
            DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);
            if (dumbService.isDumb() && !(completer instanceof DumbAware)) {
                uiCallback.accept(List.of());
                return;
            }

            CompletionParameters parameters = new CompletionParameters(project, file, text, pos);
            CompletionResultSetImpl impl = new CompletionResultSetImpl(new PrefixMatcher.Plain(""), completer);
            completer.addCompletions(parameters, impl);
            uiCallback.accept(com.tyron.nanoj.core.completion.LookupElementSorting.sort(project, parameters, impl.getResultList()));
        }
    }

    @Override
    public void dispose() {

    }
}