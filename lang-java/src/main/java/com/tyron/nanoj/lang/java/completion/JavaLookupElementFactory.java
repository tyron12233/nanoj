package com.tyron.nanoj.lang.java.completion;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.tyron.nanoj.api.completion.InsertionContext;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementBuilder;
import com.tyron.nanoj.api.model.ElementHandle;
import com.tyron.nanoj.lang.java.model.JavacElementHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JavaLookupElementFactory {

    private JavaLookupElementFactory() {
    }

    public static LookupElement keyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword is blank");
        }

        return LookupElementBuilder.create(keyword)
                .withPresentableText(keyword)
                .withIcon("keyword")
                .withBoldness(true)
                .withInsertHandler((context, item) -> maybeInsertTrailingSpaceForKeyword(context, keyword));
    }

    private static void maybeInsertTrailingSpaceForKeyword(InsertionContext context, String keyword) {
        if (context == null || context.getDocument() == null || keyword == null) {
            return;
        }

        if (!keywordNeedsTrailingSpace(keyword)) {
            return;
        }

        int offset = context.getTailOffset();
        if (offset < 0) {
            return;
        }

//        String text;
//        try {
//            text = context.getDocument().getText();
//        } catch (Throwable t) {
//            text = null;
//        }

//        if (text != null && offset < text.length()) {
//            char next = text.charAt(offset);
//            if (Character.isWhitespace(next)) {
//                return;
//            }
//        }

        context.getDocument().insertString(offset, " ");
        context.setSelectionEndOffset(context.getSelectionEndOffset() + 1);
        context.setTailOffset(context.getTailOffset() + 1);
    }

    private static boolean keywordNeedsTrailingSpace(String keyword) {
        // No trailing space for expression literals / qualifiers.
        if ("this".equals(keyword) || "super".equals(keyword)) return false;
        if ("null".equals(keyword) || "true".equals(keyword) || "false".equals(keyword)) return false;

        // Control/statement/decl keywords usually want a space after.
        return switch (keyword) {
            case "package", "import",
                 "public", "protected", "private",
                 "static", "final", "abstract",
                 "class", "interface", "enum", "record",
                 "void", "var",
                 "new",
                 "return", "throw",
                 "break", "continue",
                 "if", "for", "while", "switch", "try", "do" -> true;
            default -> false;
        };
    }

    public static LookupElementBuilder forSymbol(Symbol symbol) {
        return forSymbol(symbol, null);
    }

    /**
     * Builds a lookup element for a symbol, optionally using a substituted javac type.
     * <p>
     * This is useful for member select where the receiver is a parameterized type,
     * e.g. for {@code List<String>} we want {@code get(int)} to render as {@code String}
     * instead of the type parameter {@code E}.
     */
    public static LookupElementBuilder forSymbol(Symbol symbol, Type asMemberType) {
        if (symbol == null) {
            return null;
        }

        JavacElementHandle handle = JavacElementHandle.create(symbol);

        String lookupString = computeLookupString(symbol, handle);
        LookupElementBuilder builder = LookupElementBuilder.create(handle, lookupString)
                .withPresentableText(lookupString)
                .withIcon(iconKey(symbol, handle))
                .withStrikeout(handle.getModifiers().contains(ElementHandle.Modifier.DEPRECATED));

        if (symbol instanceof Symbol.MethodSymbol methodSymbol) {
            boolean hasArguments = false;
            try {
                hasArguments = methodSymbol.getParameters() != null && !methodSymbol.getParameters().isEmpty();
            } catch (Throwable ignored) {
            }
            try {
                hasArguments = hasArguments || methodSymbol.isVarArgs();
            } catch (Throwable ignored) {
            }

            final boolean finalHasArguments = hasArguments;
            builder.withInsertHandler((context, item) -> smartInsertMethodParentheses(context, finalHasArguments));
        }

        String tailText = computeTailText(symbol, handle, asMemberType);
        if (tailText != null && !tailText.isBlank()) {
            builder.withTailText(tailText, true);
        }

        String typeText = computeTypeText(symbol, handle, asMemberType);
        if (typeText != null && !typeText.isBlank()) {
            builder.withTypeText(typeText);
        }

        return builder;
    }

    private static void smartInsertMethodParentheses(InsertionContext context, boolean hasArguments) {
        if (context == null || context.getEditor() == null || context.getDocument() == null) {
            return;
        }

        int offset = context.getTailOffset();
        if (offset < 0) {
            return;
        }

        String text;
        try {
            text = context.getDocument().getText();
        } catch (Throwable t) {
            text = null;
        }

        int length = text != null ? text.length() : -1;
        if (text != null && offset < length && text.charAt(offset) == '(') {
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

    private static String computeLookupString(Symbol symbol, JavacElementHandle handle) {
        if (symbol instanceof Symbol.MethodSymbol methodSymbol && methodSymbol.isConstructor()) {
            return methodSymbol.owner != null ? methodSymbol.owner.name.toString() : handle.getSimpleName();
        }
        return handle.getSimpleName();
    }

    private static String computeTailText(Symbol symbol, JavacElementHandle handle, Type asMemberType) {
        if (symbol instanceof Symbol.MethodSymbol methodSymbol) {
            String params = formatParameters(methodSymbol, asMemberType);
            String ownerSuffix = ownerSuffix(symbol);
            return params + ownerSuffix;
        }

        if (handle.getKind() == ElementHandle.ElementKind.FIELD) {
            return ownerSuffix(symbol);
        }

        return null;
    }

    private static String computeTypeText(Symbol symbol, JavacElementHandle handle, Type asMemberType) {
        if (symbol instanceof Symbol.MethodSymbol methodSymbol) {
            if (methodSymbol.isConstructor()) {
                return null;
            }

            Type.MethodType mt = unwrapMethodType(asMemberType);
            if (mt != null) {
                return shortenJavaType(safeTypeToString(mt.getReturnType()));
            }

            return shortenJavaType(safeTypeToString(methodSymbol.getReturnType()));
        }

        if (symbol instanceof Symbol.VarSymbol varSymbol) {
            Type t = unwrapNonMethodType(asMemberType);
            if (t != null) {
                return shortenJavaType(safeTypeToString(t));
            }
            return shortenJavaType(safeTypeToString(varSymbol.type));
        }

        if (handle.getKind() == ElementHandle.ElementKind.CLASS
                || handle.getKind() == ElementHandle.ElementKind.INTERFACE
                || handle.getKind() == ElementHandle.ElementKind.ENUM) {
            return packageName(handle.getQualifiedName());
        }

        return null;
    }

    private static String iconKey(Symbol symbol, JavacElementHandle handle) {
        if (symbol instanceof Symbol.MethodSymbol methodSymbol && methodSymbol.isConstructor()) {
            return "constructor";
        }

        return switch (handle.getKind()) {
            case METHOD -> "method";
            case FIELD -> "field";
            case PARAMETER -> "parameter";
            case LOCAL_VARIABLE -> "variable";
            case CLASS -> "class";
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case PACKAGE -> "package";
            case MODULE -> "module";
        };
    }

    private static String ownerSuffix(Symbol symbol) {
        if (symbol == null || symbol.owner == null) {
            return "";
        }

        // Avoid noisy tails like "java.lang" for core types.
        if (symbol.owner.getQualifiedName() == null) {
            return "";
        }

        String ownerSimple = symbol.owner.name != null ? symbol.owner.name.toString() : "";
        if (ownerSimple.isBlank() || "package-info".equals(ownerSimple)) {
            return "";
        }

        return "  " + ownerSimple;
    }

    private static String formatParameters(Symbol.MethodSymbol methodSymbol, Type asMemberType) {
        List<? extends Symbol.VarSymbol> params = methodSymbol.getParameters();
        if (params == null || params.isEmpty()) {
            return "()";
        }

        Type.MethodType mt = unwrapMethodType(asMemberType);
        boolean isVarArgs = methodSymbol.isVarArgs();

        List<String> parts = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            Symbol.VarSymbol p = params.get(i);

            Type paramType = null;
            if (mt != null && mt.argtypes != null && i >= 0 && i < mt.argtypes.size()) {
                try {
                    paramType = mt.argtypes.get(i);
                } catch (Throwable ignored) {
                    paramType = null;
                }
            }
            if (paramType == null) {
                paramType = p.type;
            }

            String typeText = shortenJavaType(safeTypeToString(paramType));
            if (isVarArgs && i == params.size() - 1) {
                typeText = typeText.replace("[]", "...");
            }
            String name = p.name != null ? p.name.toString() : "";
            if (name.isBlank()) {
                parts.add(typeText);
            } else {
                parts.add(typeText + " " + name);
            }
        }

        return "(" + String.join(", ", parts) + ")";
    }

    private static Type.MethodType unwrapMethodType(Type t) {
        if (t == null) {
            return null;
        }
        try {
            if (t instanceof Type.ForAll fa) {
                t = fa.qtype;
            }
        } catch (Throwable ignored) {
        }
        return (t instanceof Type.MethodType mt) ? mt : null;
    }

    private static Type unwrapNonMethodType(Type t) {
        if (t == null) {
            return null;
        }
        try {
            if (t instanceof Type.ForAll fa) {
                t = fa.qtype;
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    private static String safeTypeToString(Type type) {
        return type == null ? "" : type.toString();
    }

    private static String packageName(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return qualifiedName.substring(0, lastDot);
    }

    static String shortenJavaType(String typeText) {
        if (typeText == null || typeText.isBlank()) {
            return typeText;
        }

        StringBuilder out = new StringBuilder(typeText.length());
        StringBuilder token = new StringBuilder();

        for (int i = 0; i < typeText.length(); i++) {
            char c = typeText.charAt(i);
            if (Character.isJavaIdentifierPart(c) || c == '.') {
                token.append(c);
            } else {
                flushTypeToken(out, token);
                out.append(c);
            }
        }
        flushTypeToken(out, token);

        return out.toString();
    }

    private static void flushTypeToken(StringBuilder out, StringBuilder token) {
        if (token.isEmpty()) {
            return;
        }

        String t = token.toString();
        token.setLength(0);

        if (!t.contains(".")) {
            out.append(t);
            return;
        }

        String[] parts = t.split("\\.");
        int firstUpper = -1;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (!p.isEmpty() && Character.isUpperCase(p.charAt(0))) {
                firstUpper = i;
                break;
            }
        }

        if (firstUpper == -1) {
            out.append(parts[parts.length - 1]);
            return;
        }

        StringBuilder joined = new StringBuilder();
        for (int i = firstUpper; i < parts.length; i++) {
            if (!joined.isEmpty()) {
                joined.append('.');
            }
            joined.append(parts[i]);
        }
        out.append(joined);
    }
}