package com.tyron.nanoj.lang.java;

import com.tyron.nanoj.api.completion.CompletionProvider;
import com.tyron.nanoj.api.diagnostics.DiagnosticsProvider;
import com.tyron.nanoj.api.editor.SyntaxHighlighter;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.lang.java.completion.JavaCompletionProvider;
import com.tyron.nanoj.lang.java.diagnostics.JavaDiagnosticsProvider;
import com.tyron.nanoj.lang.java.editor.JavaSyntaxHighlighter;

public class JavaLanguageSupport implements LanguageSupport {

    public JavaLanguageSupport(Project project) {
    }

    @Override
    public boolean canHandle(FileObject file) {
        return file.getName().endsWith(".java");
    }

    @Override
    public SyntaxHighlighter createHighlighter(Project project, FileObject file) {
        return new JavaSyntaxHighlighter(project, file);
    }

    @Override
    public CompletionProvider createCompletionProvider(Project project, FileObject file) {
        return new JavaCompletionProvider(project, file);
    }

    @Override
    public DiagnosticsProvider createDiagnosticsProvider(Project project, FileObject file) {
        return new JavaDiagnosticsProvider(project);
    }
}