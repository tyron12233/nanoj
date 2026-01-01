package com.tyron.nanoj.lang.java.completions;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.tyron.nanoj.api.completion.CompletionResultSet;
import com.tyron.nanoj.api.completion.InsertionContext;
import com.tyron.nanoj.api.completion.LookupElementBuilder;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.api.indexing.Scopes;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;
import com.tyron.nanoj.lang.java.indexing.ShortClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.JavaSuperTypeIndex;
import com.tyron.nanoj.lang.java.indexing.stub.ClassStub;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;

public class ClassNameCompletions {

    public static void addClassNames(Project project, CompilationInfo info, TreePath pathAtCursor, String partialIdentifier, CompletionResultSet result) {
        if (project == null || info == null || pathAtCursor == null || result == null) {
            return;
        }

        IndexManager indexManager = IndexManager.getInstance();

        // 1) Smart: `new <caret>` context. Suggest instantiable subtypes of the expected type.
        ExpectedNewContext expected = findExpectedNewContext(info, pathAtCursor);
        if (expected != null && expected.expectedTypeInternalName != null && !expected.expectedTypeInternalName.isBlank()) {
            int added = addExpectedTypeSubclasses(project, indexManager, expected.expectedTypeInternalName, partialIdentifier, result);
            if (added > 0) {
                return;
            }
        }

        // fallback: plain prefix-based class completion.
        indexManager.processPrefix(
                ShortClassNameIndex.ID,
                partialIdentifier,
                Scopes.all(project),
                (id, val) -> {
                    var fqn = (String) val;
                    var shortName = fqn.substring(fqn.lastIndexOf('.') + 1);
                int prio = fallbackPriority(indexManager, fqn);
                var element = classLookup(shortName, fqn)
                    .withPriority(prio);
                    result.addElement(element);
                    return true;
                }
        );
    }

    private static int addExpectedTypeSubclasses(Project project, IndexManager indexManager, String expectedInternalName, String prefix, CompletionResultSet out) {
        final int LIMIT = 50;

        String p = prefix != null ? prefix : "";
        String pLower = p.toLowerCase(Locale.ROOT);

        ArrayDeque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(expectedInternalName);
        seen.add(expectedInternalName);

        int[] added = {0};

        // include the expected type itself (concrete highest; abstract/interface are still possible via anonymous class).
        {
            String fqn = toFqn(expectedInternalName);
            String shortName = shortName(fqn);
            if (pLower.isEmpty() || shortName.toLowerCase(Locale.ROOT).startsWith(pLower)) {
                out.addElement(
                        classLookup(shortName, fqn)
                                .withPriority(newContextPriority(indexManager, expectedInternalName, true))
                );
                added[0]++;
            }
        }

        while (!q.isEmpty() && added[0] < LIMIT) {
            String superName = q.removeFirst();

            // Keys are stored as "<super>#<self>" so we do prefix search on "<super>#".
            indexManager.processPrefix(
                    JavaSuperTypeIndex.ID,
                    superName + "#",
                    Scopes.all(project),
                    (fileId, value) -> {
                        if (!(value instanceof String subInternal)) {
                            return true;
                        }

                        if (seen.add(subInternal)) {
                            q.addLast(subInternal);
                        }

                        String fqn = toFqn(subInternal);
                        String shortName = shortName(fqn);

                        if (!pLower.isEmpty() && !shortName.toLowerCase(Locale.ROOT).startsWith(pLower)) {
                            return true;
                        }

                        LookupElementBuilder el = classLookup(shortName, fqn)
                            .withPriority(newContextPriority(indexManager, subInternal, false));
                        out.addElement(el);

                        added[0]++;
                        return added[0] < LIMIT;
                    }
            );
        }

        return added[0];
    }

    private static LookupElementBuilder classLookup(String shortName, String fqn) {
        return LookupElementBuilder.create(fqn, shortName)
                .withTypeText(fqn)
                .withInsertHandler((context, item) -> handleClassInsert(context, item.getObject()));
    }

    private static void handleClassInsert(InsertionContext context, Object object) {
        if (context == null || context.getDocument() == null) {
            return;
        }

        String fqn = object instanceof String s ? s : null;
        if (fqn == null || fqn.isBlank()) {
            return;
        }

        // 1) Ensure import (if needed). This may shift offsets.
        int delta = ensureImported(context, fqn);
        if (delta != 0) {
            context.setSelectionEndOffset(context.getSelectionEndOffset() + delta);
            context.setTailOffset(context.getTailOffset() + delta);
        }

        // 2) Insert diamond type arguments for generic classes.
        boolean hasTypeParams = classHasTypeParameters(context.getProject(), fqn);
        if (hasTypeParams) {
            insertDiamondTypeArguments(context);
        }

        // 3) Insert constructor call parentheses and place caret based on constructor parameters.
        boolean hasArguments = classConstructorLikelyHasArguments(context.getProject(), fqn);
        insertConstructorParentheses(context, hasArguments);
    }

    private static void insertDiamondTypeArguments(InsertionContext context) {
        int offset = context.getTailOffset();
        if (offset < 0) return;

        String text;
        try {
            text = context.getDocument().getText();
        } catch (Throwable t) {
            text = null;
        }

        int length = text != null ? text.length() : -1;
        if (text != null && offset < length) {
            char ch = text.charAt(offset);
            if (ch == '<') {
                // Type arguments already exist; don't insert another pair.
                return;
            }
        }

        context.getDocument().insertString(offset, "<>");
        context.setSelectionEndOffset(context.getSelectionEndOffset() + 2);
        context.setTailOffset(context.getTailOffset() + 2);
    }

    private static void moveTailPastTypeArgumentsIfPresent(InsertionContext context) {
        if (context == null || context.getDocument() == null) return;

        int offset = context.getTailOffset();
        if (offset < 0) return;

        String text;
        try {
            text = context.getDocument().getText();
        } catch (Throwable t) {
            text = null;
        }
        if (text == null) return;
        if (offset >= text.length()) return;

        if (text.charAt(offset) != '<') {
            return;
        }

        int i = offset;
        int depth = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    i++; // move past the closing '>'
                    context.setSelectionEndOffset(Math.max(context.getSelectionEndOffset(), i));
                    context.setTailOffset(i);
                    return;
                }
            }
            i++;
        }
    }

    private static boolean isNewContext(InsertionContext context) {
        String text;
        try {
            text = context.getDocument().getText();
        } catch (Throwable t) {
            text = null;
        }
        if (text == null) return false;

        int start = context.getStartOffset();
        if (start < 4) return false;
        int kwStart = start - 4;
        if (!text.regionMatches(kwStart, "new ", 0, 4)) {
            return false;
        }
        int before = kwStart - 1;
        return before < 0 || !Character.isJavaIdentifierPart(text.charAt(before));
    }

    private static void insertConstructorParentheses(InsertionContext context, boolean hasArguments) {
        // If type args already exist, parentheses belong *after* them.
        moveTailPastTypeArgumentsIfPresent(context);

        int offset = context.getTailOffset();
        if (offset < 0) return;

        String text;
        try {
            text = context.getDocument().getText();
        } catch (Throwable t) {
            text = null;
        }

        int length = text != null ? text.length() : -1;
        if (text != null && offset < length && text.charAt(offset) == '(') {
            // Already has parentheses.
            if (hasArguments) {
                context.setTailOffset(offset + 1);
                return;
            }

            if (offset + 1 < length && text.charAt(offset + 1) == ')') {
                context.setTailOffset(offset + 2);
            } else {
                context.setTailOffset(offset + 1);
            }
            return;
        }

        context.getDocument().insertString(offset, "()");
        context.setSelectionEndOffset(offset + 2);

        if (hasArguments) {
            context.setTailOffset(offset + 1);
        } else {
            context.setTailOffset(offset + 2);
        }
    }

    /**
     * Heuristic: if there is a public no-arg constructor, treat it as "no arguments".
     * Otherwise, treat it as requiring arguments and place caret inside parentheses.
     */
    private static boolean classConstructorLikelyHasArguments(Project project, String fqn) {
        if (project == null || fqn == null || fqn.isBlank()) {
            return false;
        }

        try {
            IndexManager indexManager = IndexManager.getInstance();
            ClassStub stub = findStub(indexManager, fqn);
            if (stub == null || stub.methods == null || stub.methods.isEmpty()) {
                return false;
            }

            boolean sawPublicCtor = false;
            for (ClassStub.MethodStub m : stub.methods) {
                if (m == null) continue;
                if (!"<init>".equals(m.name)) continue;
                if (!Modifier.isPublic(m.accessFlags)) continue;

                sawPublicCtor = true;
                String desc = m.descriptor;
                if (desc != null && desc.startsWith("()")) {
                    return false;
                }
            }

            // If we saw any public ctor but none were no-arg, assume args are needed.
            return sawPublicCtor;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean classHasTypeParameters(Project project, String fqn) {
        if (project == null || fqn == null || fqn.isBlank()) {
            return false;
        }

        try {
            IndexManager indexManager = IndexManager.getInstance();
            ClassStub stub = findStub(indexManager, fqn);
            if (stub == null) {
                return false;
            }

            // Class signature for generics starts with type parameters: e.g. "<E:Ljava/lang/Object;>..."
            String sig = stub.signature;
            return sig != null && sig.startsWith("<");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
        * @return number of characters inserted before the caret/tail offsets.
        */
    private static int ensureImported(InsertionContext context, String fqn) {
        // No need to import java.lang
        if (fqn.startsWith("java.lang.")) {
            return 0;
        }

        String text;
        try {
            text = context.getDocument().getText();
        } catch (Throwable t) {
            return 0;
        }
        if (text == null) {
            return 0;
        }

        // If already imported (exact or wildcard), do nothing.
        if (text.contains("import " + fqn + ";")) {
            return 0;
        }
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot <= 0) {
            return 0;
        }
        String pkg = fqn.substring(0, lastDot);
        if (text.contains("import " + pkg + ".*;")) {
            return 0;
        }

        String currentPkg = parsePackageName(text);
        if (currentPkg != null && !currentPkg.isBlank() && currentPkg.equals(pkg)) {
            return 0;
        }

        int insertAt = computeImportInsertOffset(text);
        String importText = buildImportInsertion(text, insertAt, fqn);
        if (importText.isEmpty()) {
            return 0;
        }

        context.getDocument().insertString(insertAt, importText);

        // Only shifts offsets if inserted before the identifier insertion point.
        return insertAt <= context.getStartOffset() ? importText.length() : 0;
    }

    private static String parsePackageName(String text) {
        int pkgIdx = text.indexOf("package ");
        if (pkgIdx < 0) return "";
        int semi = text.indexOf(';', pkgIdx);
        if (semi < 0) return "";
        String inside = text.substring(pkgIdx + "package ".length(), semi).trim();
        return inside;
    }

    private static int computeImportInsertOffset(String text) {
        int pkgIdx = text.indexOf("package ");
        int scanFrom = 0;
        if (pkgIdx >= 0) {
            int semi = text.indexOf(';', pkgIdx);
            if (semi >= 0) {
                scanFrom = semi + 1;
                // Move to end of that line.
                int nl = text.indexOf('\n', scanFrom);
                if (nl >= 0) scanFrom = nl + 1;
            }
        }

        int lastImportEnd = -1;
        int i = scanFrom;
        while (i < text.length()) {
            // Skip blank lines and whitespace.
            int lineStart = i;
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.substring(lineStart, lineEnd);
            String trimmed = line.trim();

            if (trimmed.startsWith("import ")) {
                lastImportEnd = lineEnd + (lineEnd < text.length() ? 1 : 0);
                i = lastImportEnd;
                continue;
            }

            // Stop scanning once we hit non-import code.
            break;
        }

        return lastImportEnd >= 0 ? lastImportEnd : scanFrom;
    }

    private static String buildImportInsertion(String text, int insertAt, String fqn) {
        StringBuilder sb = new StringBuilder();
        // If inserting right after a package line with no blank line, add one.
        if (insertAt > 0 && insertAt <= text.length()) {
            // If we're at start of a non-empty line and previous char wasn't a newline, ensure newline.
            if (text.charAt(insertAt - 1) != '\n') {
                sb.append('\n');
            }
        }
        sb.append("import ").append(fqn).append(";\n");

        // Ensure a blank line after the last import before code.
        if (insertAt < text.length()) {
            if (text.charAt(insertAt) != '\n') {
                sb.append('\n');
            } else {
                // If next char is newline, keep a single blank line by adding one more.
                sb.append('\n');
            }
        } else {
            sb.append('\n');
        }

        return sb.toString();
    }

    private enum NewContextKind {
        CONCRETE,
        ABSTRACT_CLASS,
        INTERFACE,
        UNKNOWN
    }

    private static int newContextPriority(IndexManager indexManager, String internalName, boolean isExpectedType) {
        NewContextKind kind = getNewContextKind(indexManager, internalName);

        // Concrete types must win over abstract/interface types.
        int base;
        switch (kind) {
            case CONCRETE -> base = 110;
            case ABSTRACT_CLASS -> base = 90;
            case INTERFACE -> base = 80;
            case UNKNOWN -> base = 95;
            default -> base = 95;
        }

        // Prefer the expected type itself slightly above subtypes.
        return isExpectedType ? base : (base - 10);
    }

    private static NewContextKind getNewContextKind(IndexManager indexManager, String internalOrFqn) {
        try {
            ClassStub stub = findStub(indexManager, internalOrFqn);
            if (stub == null) {
                return NewContextKind.UNKNOWN;
            }

            int flags = stub.accessFlags;
            if (Modifier.isInterface(flags)) {
                return NewContextKind.INTERFACE;
            }
            if (Modifier.isAbstract(flags)) {
                return NewContextKind.ABSTRACT_CLASS;
            }
            return NewContextKind.CONCRETE;
        } catch (Throwable ignored) {
            return NewContextKind.UNKNOWN;
        }
    }

    private static int fallbackPriority(IndexManager indexManager, String fqn) {
        try {
            ClassStub stub = findStub(indexManager, fqn);
            if (stub == null) {
                return 0;
            }
            int flags = stub.accessFlags;
            if (Modifier.isInterface(flags)) return -10;
            if (Modifier.isAbstract(flags)) return 0;
            return 10;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static ClassStub findStub(IndexManager indexManager, String internalOrFqn) {
        if (indexManager == null || internalOrFqn == null || internalOrFqn.isBlank()) {
            return null;
        }

        String internal = internalOrFqn.contains("/") ? internalOrFqn : internalOrFqn.replace('.', '/');

        ClassStub stub = firstStub(indexManager, internal);
        if (stub != null) return stub;

        // Heuristic for nested classes: progressively replace rightmost '/' with '$'.
        String candidate = internal;
        int slash = candidate.lastIndexOf('/');
        while (slash > 0) {
            candidate = candidate.substring(0, slash) + '$' + candidate.substring(slash + 1);
            stub = firstStub(indexManager, candidate);
            if (stub != null) return stub;
            slash = candidate.lastIndexOf('/', slash - 1);
        }

        return null;
    }

    private static ClassStub firstStub(IndexManager indexManager, String internalName) {
        List<ClassStub> stubs = indexManager.search(JavaBinaryStubIndexer.ID, internalName);
        if (stubs == null || stubs.isEmpty()) {
            return null;
        }
        return stubs.get(0);
    }

    private static String toFqn(String internalName) {
        if (internalName == null) {
            return "";
        }
        return internalName.replace('/', '.').replace('$', '.');
    }

    private static String shortName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }

    private static ExpectedNewContext findExpectedNewContext(CompilationInfo info, TreePath pathAtCursor) {
        Trees trees = Trees.instance(info.getTask());

        TreePath newClassPath = null;
        for (TreePath p = pathAtCursor; p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            if (leaf != null && leaf.getKind() == Tree.Kind.NEW_CLASS) {
                newClassPath = p;
                break;
            }
        }

        if (newClassPath == null) {
            return null;
        }

        // Walk up the *expression* tree and discover expected type from usage context.
        TreePath exprPath = newClassPath;

        for (TreePath p = newClassPath.getParentPath(); p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            if (leaf == null) {
                continue;
            }

            // Handle explicit casts: `(SomeType) new <caret>`.
            if (leaf instanceof TypeCastTree castTree && castTree.getExpression() == exprPath.getLeaf()) {
                Tree typeTree = castTree.getType();
                if (typeTree == null) {
                    return null;
                }
                TypeMirror tm = trees.getTypeMirror(new TreePath(p, typeTree));
                return ExpectedNewContext.fromTypeMirror(tm);
            }

            // Parentheses don't change meaning.
            if (leaf instanceof ParenthesizedTree pt && pt.getExpression() == exprPath.getLeaf()) {
                exprPath = p;
                continue;
            }

            // Variable initializer: `Type x = <expr>`.
            if (leaf instanceof VariableTree vt && vt.getInitializer() == exprPath.getLeaf()) {
                Tree typeTree = vt.getType();
                if (typeTree == null) {
                    return null;
                }
                TypeMirror tm = trees.getTypeMirror(new TreePath(p, typeTree));
                return ExpectedNewContext.fromTypeMirror(tm);
            }

            // Assignment: `lhs = <expr>`.
            if (leaf instanceof AssignmentTree at && at.getExpression() == exprPath.getLeaf()) {
                ExpressionTree lhs = at.getVariable();
                if (lhs == null) {
                    return null;
                }
                TypeMirror tm = trees.getTypeMirror(new TreePath(p, lhs));
                return ExpectedNewContext.fromTypeMirror(tm);
            }

            // Return: `return <expr>`.
            if (leaf instanceof ReturnTree rt && rt.getExpression() == exprPath.getLeaf()) {
                TypeMirror tm = expectedFromEnclosingMethodReturn(trees, p);
                return ExpectedNewContext.fromTypeMirror(tm);
            }

            // Argument position: `call(..., <expr>, ...)`.
            if (leaf instanceof MethodInvocationTree mit) {
                int idx = indexOfArg(mit, exprPath.getLeaf());
                if (idx >= 0) {
                    TypeMirror tm = expectedFromInvocationArg(trees, p, idx);
                    return ExpectedNewContext.fromTypeMirror(tm);
                }
            }

            // If the new-expression is nested, bubble up through expression wrappers.
            if (leaf instanceof ConditionalExpressionTree cet) {
                if (cet.getTrueExpression() == exprPath.getLeaf() || cet.getFalseExpression() == exprPath.getLeaf()) {
                    exprPath = p;
                    continue;
                }
            }

            // Keep climbing, but only while we're inside expressions.
            if (leaf instanceof ExpressionTree) {
                exprPath = p;
                continue;
            }

            // Reached a non-expression container without finding an expected type.
            break;
        }

        return null;
    }

    private static TypeMirror expectedFromEnclosingMethodReturn(Trees trees, TreePath anyPathInsideMethod) {
        if (trees == null || anyPathInsideMethod == null) {
            return null;
        }

        for (TreePath p = anyPathInsideMethod; p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            if (leaf != null && leaf.getKind() == Tree.Kind.METHOD) {
                // For methods, Trees.getElement(MethodTreePath) should be an ExecutableElement.
                try {
                    Element el = trees.getElement(p);
                    if (el instanceof ExecutableElement ee) {
                        return ee.getReturnType();
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }
        }

        return null;
    }

    private static int indexOfArg(MethodInvocationTree mit, Tree argLeaf) {
        if (mit == null || argLeaf == null) {
            return -1;
        }
        List<? extends ExpressionTree> args = mit.getArguments();
        if (args == null) {
            return -1;
        }
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) == argLeaf) {
                return i;
            }
        }
        return -1;
    }

    private static TypeMirror expectedFromInvocationArg(Trees trees, TreePath invocationPath, int argIndex) {
        if (trees == null || invocationPath == null || argIndex < 0) {
            return null;
        }

        Element el;
        try {
            el = trees.getElement(invocationPath);
        } catch (Throwable t) {
            el = null;
        }

        if (!(el instanceof ExecutableElement ee)) {
            return null;
        }

        List<? extends VariableElement> params = ee.getParameters();
        if (params == null || params.isEmpty()) {
            return null;
        }

        int paramIndex = Math.min(argIndex, params.size() - 1);
        TypeMirror paramType = params.get(paramIndex).asType();

        // If varargs and the argument lands in the vararg slot, use component type.
        try {
            if (ee.isVarArgs() && argIndex >= params.size() - 1 && paramType instanceof ArrayType at) {
                return at.getComponentType();
            }
        } catch (Throwable ignored) {
        }

        return paramType;
    }

    private static final class ExpectedNewContext {
        final String expectedTypeInternalName;

        private ExpectedNewContext(String expectedTypeInternalName) {
            this.expectedTypeInternalName = expectedTypeInternalName;
        }

        static ExpectedNewContext fromTypeMirror(javax.lang.model.type.TypeMirror tm) {
            if (tm == null) return null;
            if (!(tm instanceof javax.lang.model.type.DeclaredType dt)) return null;
            var el = dt.asElement();
            if (!(el instanceof javax.lang.model.element.TypeElement te)) return null;
            String qn = te.getQualifiedName() != null ? te.getQualifiedName().toString() : "";
            if (qn.isBlank()) return null;
            return new ExpectedNewContext(qn.replace('.', '/'));
        }
    }
}
