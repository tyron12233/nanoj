package com.tyron.nanoj.lang.java.completions;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.tyron.nanoj.api.completion.CompletionResultSet;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;
import com.tyron.nanoj.lang.java.completion.JavaLookupElementFactory;
import me.xdrop.fuzzywuzzy.FuzzySearch;

import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tyron.nanoj.lang.java.completion.JavaLookupElementFactory.keyword;
import static com.tyron.nanoj.lang.java.util.ElementUtil.isEnclosingClass;

public class MemberSelectCompletions {

    public static void addMemberSelectCompletions(CompilationInfo info, TreePath currentPath, String partialIdentifier, CompletionResultSet result) {
        TreePath memberSelectPath = currentPath;
        MemberSelectTree select = ((MemberSelectTree) memberSelectPath.getLeaf());
        Trees trees = Trees.instance(info.getTask());

        String receiverText;
        try {
            receiverText = String.valueOf(select.getExpression());
        } catch (Throwable t) {
            receiverText = null;
        }

        if (("this".equals(receiverText) || "super".equals(receiverText)) && isInStaticContext(trees, memberSelectPath)) {
            return;
        }

        currentPath = new TreePath(memberSelectPath, select.getExpression());

        Element element;
        try {
            element = trees.getElement(currentPath);
        } catch (Throwable t) {
            element = null;
        }
        boolean isStatic = element instanceof TypeElement;

        Scope scope = trees.getScope(currentPath);
        TypeMirror type = trees.getTypeMirror(currentPath);

        if (type instanceof ArrayType) {
            completeArrayMemberSelect(result, isStatic);
        } else if (type instanceof DeclaredType) {
            completeDeclaredTypeMemberSelect(result, info.getTask(), scope, (DeclaredType) type, isStatic, partialIdentifier);
        }
    }

    private static boolean isInStaticContext(Trees trees, TreePath path) {
        if (trees == null || path == null) {
            return false;
        }

        try {
            Scope scope = trees.getScope(path);
            if (scope != null) {
                ExecutableElement enclosing = scope.getEnclosingMethod();
                if (enclosing != null && enclosing.getModifiers().contains(Modifier.STATIC)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        for (TreePath p = path; p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            if (leaf instanceof BlockTree blockTree) {
                try {
                    if (blockTree.isStatic()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (leaf instanceof VariableTree variableTree) {
                try {
                    if (variableTree.getModifiers() != null && variableTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return false;
    }

    private static void completeDeclaredTypeMemberSelect(CompletionResultSet result, JavacTaskImpl task, Scope scope, DeclaredType type, boolean isStatic, String partialIdentifier) {
        Trees trees = Trees.instance(task);
        TypeElement typeElement = (TypeElement) type.asElement();

        Types types;
        try {
            types = Types.instance(task.getContext());
        } catch (Throwable t) {
            types = null;
        }

        Type site;
        try {
            site = (type instanceof Type t) ? t : null;
        } catch (Throwable t) {
            site = null;
        }

        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) {
                continue;
            }

            if (FuzzySearch.tokenSetPartialRatio(String.valueOf(member.getSimpleName()), partialIdentifier) < 70 &&
                    !partialIdentifier.endsWith(".") &&
                    !partialIdentifier.isEmpty()) {
                continue;
            }

            if (!trees.isAccessible(scope, member, type)) {
                continue;
            }
            if (isStatic !=
                    member.getModifiers()
                            .contains(Modifier.STATIC)) {
                continue;
            }

            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                Symbol sym = (Symbol) member;
                Type asMemberType = null;
                if (types != null && site != null) {
                    try {
                        asMemberType = types.memberType(site, sym);
                    } catch (Throwable ignored) {
                        asMemberType = null;
                    }
                }
                result.addElement(JavaLookupElementFactory.forSymbol(sym, asMemberType));
            }
        }

        for (List<ExecutableElement> overloads : methods.values()) {
            for (ExecutableElement overload : overloads) {
                Symbol sym = (Symbol) overload;
                Type asMemberType = null;
                if (types != null && site != null) {
                    try {
                        asMemberType = types.memberType(site, sym);
                    } catch (Throwable ignored) {
                        asMemberType = null;
                    }
                }
                result.addElement(JavaLookupElementFactory.forSymbol(sym, asMemberType));
            }
        }

        if (isStatic) {
            if (FuzzySearch.tokenSetPartialRatio("class", partialIdentifier) > 70) {
                result.addElement(keyword("class"));
            }
        }

        if (isStatic && isEnclosingClass(type, scope)) {
            if (FuzzySearch.tokenSetPartialRatio("this", partialIdentifier) >= 70) {
                result.addElement(keyword("this"));
            }
            if (FuzzySearch.tokenSetPartialRatio("super", partialIdentifier) >= 70) {
                result.addElement(keyword("super"));
            }
        }
    }

    public static void putMethod(ExecutableElement method,
                                 Map<String, List<ExecutableElement>> methods) {
        String name = method.getSimpleName()
                .toString();
        if (!methods.containsKey(name)) {
            methods.put(name, new ArrayList<>());
        }
        List<ExecutableElement> elements = methods.get(name);
        if (elements != null) {
            elements.add(method);
        }
    }

    private static void completeArrayMemberSelect(CompletionResultSet builder, boolean isStatic) {
        if (!isStatic) {
            builder.addElement(keyword("length"));
        }
    }
}
