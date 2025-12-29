package com.tyron.nanoj.lang.java.completion;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.tyron.nanoj.api.completion.CompletionParameters;
import com.tyron.nanoj.api.completion.CompletionProvider;
import com.tyron.nanoj.api.completion.CompletionResultSet;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.lang.java.completions.*;
import com.tyron.nanoj.lang.java.source.JavaSource;
import org.jetbrains.annotations.NotNull;

public final class JavaCompletionProvider implements CompletionProvider {

    private final Project project;
    private final FileObject file;

    public JavaCompletionProvider(Project project, FileObject file) {
        this.project = project;
        this.file = file;
    }

    @Override
    public void addCompletions(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        JavaSource source = JavaSource.forFile(project, file);

        CharSequence fixed = new FileContentFixer(new Context()).fixFileContentForCompletion(parameters.text(), parameters.offset());
        int offset = Math.min(parameters.offset(), fixed.length());
        String identifier = StringSearch.partialIdentifier(fixed.toString(), offset);

        source.runUserActionTask(fixed.toString(), info -> {
            JCTree.JCCompilationUnit compilationUnit = info.getCompilationUnit();
            TreePath pathAtCursor = new FindCompletionsAt(info.getTask()).scan(compilationUnit, (long) offset);
            if (pathAtCursor == null || pathAtCursor.getLeaf() == null) {
                return null;
            }

            switch (pathAtCursor.getLeaf().getKind()) {
                case IMPORT -> {
                    PackageCompletions.add(project, info, pathAtCursor, fixed.toString(), offset, identifier, result);
                }
                case IDENTIFIER -> {
                    KeywordCompletions.addKeywords(info, pathAtCursor, offset, identifier, result);
                    PackageCompletions.add(project, info, pathAtCursor, fixed.toString(), offset, identifier, result);
                    ScopeCompletions.addCompletionItems(info, pathAtCursor, identifier, false, result);
                    ClassNameCompletions.addClassNames(project, info, pathAtCursor, identifier, result);
                }
                case MEMBER_SELECT -> {
                    PackageCompletions.add(project, info, pathAtCursor, fixed.toString(), offset, identifier, result);
                    MemberSelectCompletions.addMemberSelectCompletions(info, pathAtCursor, identifier, result);
                }
                default -> {
                }
            }

            return null;
        }).join();
    }
}