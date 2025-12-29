package com.tyron.nanoj.core.diagnostics;

import com.tyron.nanoj.api.diagnostics.Diagnostic;
import com.tyron.nanoj.api.diagnostics.DiagnosticsProvider;
import com.tyron.nanoj.api.diagnostics.ErrorHighlightingService;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.FileDocumentManagerImpl;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.util.List;
import java.util.Objects;

/**
 * Core implementation of {@link ErrorHighlightingService}.
 *
 * Delegates to a language-specific {@link DiagnosticsProvider} via {@link LanguageSupport}.
 */
public final class ErrorHighlightingServiceImpl implements ErrorHighlightingService {

    public static ErrorHighlightingServiceImpl getInstance(Project project) {
        return ProjectServiceManager.getService(project, ErrorHighlightingServiceImpl.class);
    }

    private final Project project;

    public ErrorHighlightingServiceImpl(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    @Override
    public List<Diagnostic> getDiagnostics(FileObject file, String text) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(text, "text");

        // Ensure providers see unsaved text when they choose to read from FileObject.
        FileObject inMemoryView = FileDocumentManagerImpl.getInstance(project).getInMemoryView(file);

        LanguageSupport support = findLanguageSupport(inMemoryView);
        DiagnosticsProvider provider = support.createDiagnosticsProvider(project, inMemoryView);
        if (provider == null) {
            return List.of();
        }

        return provider.getDiagnostics(inMemoryView, text);
    }

    private LanguageSupport findLanguageSupport(FileObject file) {
        List<LanguageSupport> supports = ProjectServiceManager.getExtensions(project, LanguageSupport.class);
        for (LanguageSupport s : supports) {
            try {
                if (s != null && s.canHandle(file)) {
                    return s;
                }
            } catch (Throwable ignored) {
            }
        }

        throw new IllegalStateException("No LanguageSupport registered for file: " + file.getName());
    }
}
