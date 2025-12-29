package com.tyron.nanoj.lang.java.editor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreeScanner;
import com.tyron.nanoj.api.editor.SyntaxHighlighter;
import com.tyron.nanoj.api.editor.TokenSpan;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.lang.java.source.JavaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JavaSyntaxHighlighter implements SyntaxHighlighter {

    private final Project project;
    private final FileObject file;

    public JavaSyntaxHighlighter(Project project, FileObject file) {
        this.project = project;
        this.file = file;
    }

    @Override
    public CompletableFuture<List<TokenSpan>> highlight(String content) {
        JavaSource source = JavaSource.forFile(project, file);

        return source.runModificationTask(content, info -> new ArrayList<>());
    }
}