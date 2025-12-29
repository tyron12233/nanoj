package com.tyron.nanoj.lang.java.completions;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.tyron.nanoj.api.completion.CompletionResultSet;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;
import com.tyron.nanoj.lang.java.completion.JavaLookupElementFactory;

import java.util.LinkedHashSet;
import java.util.Set;

public final class KeywordCompletions {

    private KeywordCompletions() {
    }

    public static void addKeywords(CompilationInfo info, TreePath pathAtCursor, int offset, String partialIdentifier, CompletionResultSet result) {
        if (info == null || pathAtCursor == null || result == null) {
            return;
        }

        Trees trees = Trees.instance(info.getTask());
        CompilationUnitTree cu = info.getCompilationUnit();

        ContextKind kind = determineContextKind(trees, cu, pathAtCursor, offset);

        Set<String> keywords = new LinkedHashSet<>();
        switch (kind) {
            case TOP_LEVEL -> {
                // Keep this intentionally conservative.
                if (cu.getPackageName() == null) {
                    keywords.add("package");
                }
                keywords.add("import");
                keywords.add("public");
                keywords.add("class");
                keywords.add("interface");
                keywords.add("enum");
                keywords.add("record");
            }
            case CLASS_BODY -> {
                keywords.add("public");
                keywords.add("protected");
                keywords.add("private");
                keywords.add("static");
                keywords.add("final");
                keywords.add("abstract");
                keywords.add("class");
                keywords.add("interface");
                keywords.add("enum");
                keywords.add("record");
                keywords.add("void");
            }
            case CODE_BLOCK -> {
                keywords.add("if");
                keywords.add("for");
                keywords.add("while");
                keywords.add("do");
                keywords.add("switch");
                keywords.add("try");
                keywords.add("return");
                keywords.add("throw");
                keywords.add("break");
                keywords.add("continue");
                keywords.add("new");
                keywords.add("this");
                keywords.add("super");
                keywords.add("null");
                keywords.add("true");
                keywords.add("false");
                keywords.add("final");
                keywords.add("var");
            }
        }

        for (String kw : keywords) {
            // Prefix matching is handled by CompletionResultSet.
            result.addElement(JavaLookupElementFactory.keyword(kw));
        }
    }

    private enum ContextKind {
        TOP_LEVEL,
        CLASS_BODY,
        CODE_BLOCK
    }

    private static ContextKind determineContextKind(Trees trees, CompilationUnitTree cu, TreePath pathAtCursor, int offset) {
        if (trees == null || cu == null || pathAtCursor == null) {
            return ContextKind.TOP_LEVEL;
        }

        // If we're inside a method body or initializer block, suggest statement keywords.
        if (isInsideExecutableBlock(trees, cu, pathAtCursor, offset)) {
            return ContextKind.CODE_BLOCK;
        }

        // If we have an enclosing class, assume class body.
        for (TreePath p = pathAtCursor; p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            if (leaf instanceof ClassTree) {
                return ContextKind.CLASS_BODY;
            }
        }

        return ContextKind.TOP_LEVEL;
    }

    private static boolean isInsideExecutableBlock(Trees trees, CompilationUnitTree cu, TreePath pathAtCursor, int offset) {
        var pos = trees.getSourcePositions();

        for (TreePath p = pathAtCursor; p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            if (leaf instanceof MethodTree mt) {
                BlockTree body = mt.getBody();
                if (body == null) {
                    return false;
                }
                long start = pos.getStartPosition(cu, body);
                long end = pos.getEndPosition(cu, body);
                return start <= offset && offset <= end;
            }

            // Instance/static initializer blocks appear as BlockTree directly inside a class.
            if (leaf instanceof BlockTree bt) {
                TreePath parent = p.getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree) {
                    long start = pos.getStartPosition(cu, bt);
                    long end = pos.getEndPosition(cu, bt);
                    return start <= offset && offset <= end;
                }
            }
        }

        return false;
    }
}
