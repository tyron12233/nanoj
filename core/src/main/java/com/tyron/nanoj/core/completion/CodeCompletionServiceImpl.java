package com.tyron.nanoj.core.completion;

import com.tyron.nanoj.api.completion.*;
import com.tyron.nanoj.api.dumb.DumbAware;
import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.FileDocumentManagerImpl;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Core implementation of {@link CodeCompletionService}.
 * <p>
 * Uses {@link LanguageSupport} to create a {@link CompletionProvider} per file.
 * Reads the current text from the caller (typically from an in-memory {@link com.tyron.nanoj.api.editor.Document}).
 * <p>
 * <b>Thread Safety:</b> This service is thread-safe. It coordinates with:
 * - IndexManager (protected by ReadWriteLock for concurrent reads during indexing)
 * - FileDocumentManager (thread-safe document access)
 * - Document text (synchronized StringBuilder updates)
 * Concurrent calls to getCompletions() from multiple threads are safe and will not corrupt state.
 */
public final class CodeCompletionServiceImpl implements CodeCompletionService {

    public static CodeCompletionServiceImpl getInstance(Project project) {
        return ProjectServiceManager.getService(project, CodeCompletionServiceImpl.class);
    }

    private final Project project;

    public CodeCompletionServiceImpl(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    @Override
    public List<LookupElement> getCompletions(FileObject file, String text, int offset) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(text, "text");

        // ensure providers see unsaved text when they choose to read from FileObject.
        FileObject inMemoryView = FileDocumentManagerImpl.getInstance(project).getInMemoryView(file);

        CompletionProvider provider = findLanguageSupport(inMemoryView).createCompletionProvider(project, inMemoryView);

        DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);
        if (dumbService.isDumb() && !(provider instanceof DumbAware)) {
            return List.of();
        }

        // prefix matching: simplest default is "" (providers can narrow themselves via ResultSet withPrefixMatcher).
        CompletionResultSetImpl resultSet = new CompletionResultSetImpl(new PrefixMatcher.Plain(""), provider);
        CompletionParameters parameters = new CompletionParameters(project, inMemoryView, text, offset);
        provider.addCompletions(parameters, resultSet);

        return LookupElementSorting.sort(project, parameters, new ArrayList<>(resultSet.getResultList()));
    }

    private LanguageSupport findLanguageSupport(FileObject file) {
        List<LanguageSupport> languages = ProjectServiceManager.getExtensions(project, LanguageSupport.class);
        return languages.stream()
                .filter(lang -> lang.canHandle(file))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No LanguageSupport found for file: " + file.getPath()));
    }
}
