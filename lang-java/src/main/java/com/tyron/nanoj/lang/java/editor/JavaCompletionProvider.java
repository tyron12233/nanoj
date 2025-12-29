package com.tyron.nanoj.lang.java.editor;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;

import com.tyron.nanoj.api.completion.*;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.lang.java.completions.FileContentFixer;
import com.tyron.nanoj.lang.java.completions.FindCompletionsAt;
import com.tyron.nanoj.lang.java.completions.ScopeCompletions;
import com.tyron.nanoj.lang.java.completions.StringSearch;
import com.tyron.nanoj.lang.java.source.JavaSource;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JavaCompletionProvider implements CompletionProvider {

    private final Project project;
    private final FileObject file;

    public JavaCompletionProvider(Project project, FileObject file) {
        this.project = project;
        this.file = file;
    }

    @Override
    public void addCompletions(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        JavaSource source = JavaSource.forFile(project, file);

        FileContentFixer fileContentFixer = new FileContentFixer(new Context());
        CharSequence fixed = fileContentFixer.fixFileContent(parameters.text());
        String identifier = StringSearch.partialIdentifier(fixed.toString(), parameters.offset());

        source.runUserActionTask(fixed.toString(), info -> {

            var trees = Trees.instance(info.getTask());
            var elements = info.getTask().getElements();

            JCTree.JCCompilationUnit compilationUnit = info.getCompilationUnit();

            TreePath pathAtCursor = new FindCompletionsAt(info.getTask()).scan(compilationUnit, ((long) parameters.offset() ));
            if (pathAtCursor == null || pathAtCursor.getLeaf() == null) {
                return List.of();
            }

            List<LookupElement> lookupElements = new ArrayList<>();

            LookupElementBuilder.create("");
            switch (pathAtCursor.getLeaf().getKind()) {
                case IDENTIFIER -> {
                    ScopeCompletions.addCompletionItems(info, pathAtCursor, identifier, false, result);
                }
            }

            return null;
        }).join();
    }
}