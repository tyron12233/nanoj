package com.tyron.nanoj.lang.java.completions;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.Symbol;
import com.tyron.nanoj.api.completion.*;
import com.tyron.nanoj.lang.java.completion.JavaLookupElementFactory;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;
import com.tyron.nanoj.lang.java.source.CancellationException;
import me.xdrop.fuzzywuzzy.FuzzySearch;

import javax.lang.model.element.Element;
import java.util.List;
import java.util.function.Predicate;

public class ScopeCompletions {

    private static final int FUZZY_THRESHOLD = 70;

    public static void addCompletionItems(
            CompilationInfo info,
            TreePath path,
            String partial,
            boolean endsWithParen,
            CompletionResultSet builder) {

        Trees trees = Trees.instance(info.getTask());
        Scope scope = trees.getScope(path);

        addScopeMembers(info, scope, partial, builder);
        addTopLevelClassNames(info.getCompilationUnit(), builder);
    }

    private static void addScopeMembers(CompilationInfo info, Scope scope, String query, CompletionResultSet builder) {
        List<Element> elements = ScopeHelper.scopeMembers(info, scope, createFuzzyFilter(query));

        for (Element element : elements) {
            checkCancellation();

            // Map the element to a handle and add to results
            Symbol symbol = (Symbol) element;
            LookupElement lookup = JavaLookupElementFactory.forSymbol(symbol);
            if (lookup != null) {
                builder.addElement(lookup);
            }
        }
    }

    private static void addTopLevelClassNames(CompilationUnitTree root, CompletionResultSet builder) {
        if (root == null) return;

        root.getTypeDecls().stream()
                .filter(tree -> tree.getKind() == Tree.Kind.CLASS)
                .map(tree -> (ClassTree) tree)
                .forEach(classTree -> {
                    String className = classTree.getSimpleName().toString();
                    builder.addElement(LookupElementBuilder.create(className));
                });
    }

    private static Predicate<CharSequence> createFuzzyFilter(String query) {
        return labelSeq -> {
            String label = labelSeq.toString();
            // Strip parameters if present (e.g., "myMethod(int)" -> "myMethod")
            String cleanLabel = label.split("\\(")[0];
            return FuzzySearch.tokenSetPartialRatio(cleanLabel, query) >= FUZZY_THRESHOLD;
        };
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}