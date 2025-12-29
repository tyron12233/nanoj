package com.tyron.nanoj.lang.java.completions;

import com.sun.source.util.TreePath;
import com.tyron.nanoj.api.completion.CompletionResultSet;
import com.tyron.nanoj.api.completion.LookupElementBuilder;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.indexing.Scopes;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Completes Java packages and types for fully-qualified references and import statements.
 */
public final class PackageCompletions {

    private PackageCompletions() {
    }

    public static void add(Project project,
                           CompilationInfo info,
                           TreePath pathAtCursor,
                           String fullText,
                           int offset,
                           String partialIdentifier,
                           CompletionResultSet result) {
        if (project == null || info == null || pathAtCursor == null || fullText == null || result == null) {
            return;
        }

        // Build the qualified chain: e.g. "java.util.co" or "java.util.".
        int chainStart = findQualifiedChainStart(fullText, offset);
        if (chainStart < 0 || chainStart >= offset) {
            return;
        }

        String chain = fullText.substring(chainStart, offset);
        int lastDot = chain.lastIndexOf('.');
        if (lastDot < 0) {
            return;
        }

        String base = chain.substring(0, lastDot + 1); // includes trailing dot
        String segPrefix = chain.substring(lastDot + 1);

        // if caller provided a partialIdentifier, prefer it (it should match segPrefix).
        String prefix = partialIdentifier != null ? partialIdentifier : segPrefix;
        if (!segPrefix.equals(prefix)) {
            // keep behavior robust even if scanners disagree.
            prefix = segPrefix;
        }

        String basePkg = trimTrailingDot(base);
        String searchPrefix = basePkg.isEmpty() ? prefix : (basePkg + "." + prefix);

        IndexManager indexManager = IndexManager.getInstance(project);

        Set<String> seenPackages = new HashSet<>();
        Set<String> seenClasses = new HashSet<>();

        // suggest immediate child packages under base.
        String finalPrefix1 = prefix;
        indexManager.processPrefixWithKeys(
                JavaPackageIndex.ID,
                basePkg.isEmpty() ? prefix : (basePkg + "." + prefix),
                Scopes.all(project),
                (key, fileId, entry) -> {
                    if (key == null) return true;
                    if (basePkg.isEmpty()) {
                        // key: "java.util.concurrent" -> suggest "java" (first segment)
                        String seg = firstSegment(key);
                        if (seg == null) return true;
                        if (finalPrefix1.isEmpty() || seg.toLowerCase(Locale.ROOT).startsWith(finalPrefix1.toLowerCase(Locale.ROOT))) {
                            if (seenPackages.add(seg)) {
                                result.addElement(packageSegment(seg));
                            }
                        }
                        return true;
                    }

                    if (!key.startsWith(basePkg)) {
                        return true;
                    }
                    if (key.length() == basePkg.length()) {
                        // Exact package; handled below for types.
                        return true;
                    }
                    if (key.charAt(basePkg.length()) != '.') {
                        return true;
                    }

                    String suffix = key.substring(basePkg.length() + 1);
                    String seg = firstSegment(suffix);
                    if (seg == null) {
                        return true;
                    }

                    if (!finalPrefix1.isEmpty() && !seg.toLowerCase(Locale.ROOT).startsWith(finalPrefix1.toLowerCase(Locale.ROOT))) {
                        return true;
                    }

                    if (seenPackages.add(seg)) {
                        result.addElement(packageSegment(seg));
                    }

                    return true;
                }
        );

        // suggest types in the exact base package (e.g. for "java.util.<caret>" suggest List, ArrayList).
        if (!basePkg.isEmpty()) {
            String finalPrefix = prefix;
            indexManager.processPrefixWithKeys(
                    JavaPackageIndex.ID,
                    basePkg,
                    Scopes.all(project),
                    (key, fileId, entry) -> {
                        var packageEntry = (JavaPackageIndex.Entry) entry;

                        if (key == null || !key.equals(basePkg) || entry == null || packageEntry.simpleName == null) {
                            return true;
                        }


                        String name = packageEntry.simpleName;
                        if (!finalPrefix.isEmpty() && !name.toLowerCase(Locale.ROOT).startsWith(finalPrefix.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                        if (seenClasses.add(name)) {
                            String fqn = basePkg + "." + name;
                            result.addElement(
                                    LookupElementBuilder.create(name)
                                            .withTypeText(fqn)
                            );
                        }
                        return true;
                    }
            );
        }
    }

    private static LookupElementBuilder packageSegment(String segment) {
        return LookupElementBuilder.create(segment)
                .withTypeText("package")
                .withPriority(20)
                .withInsertHandler((context, item) -> {
                    if (context == null || context.getDocument() == null) return;
                    int off = context.getTailOffset();
                    if (off < 0) return;

                    String text;
                    try {
                        text = context.getDocument().getText();
                    } catch (Throwable t) {
                        text = null;
                    }

                    if (text != null && off < text.length()) {
                        char next = text.charAt(off);
                        if (next == '.' || next == ';') {
                            return;
                        }
                    }

                    context.getDocument().insertString(off, ".");
                    context.setSelectionEndOffset(context.getSelectionEndOffset() + 1);
                    context.setTailOffset(context.getTailOffset() + 1);
                });
    }

    private static int findQualifiedChainStart(String text, int offset) {
        if (text == null) return -1;
        int i = Math.min(offset, text.length());
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.') {
                i--;
                continue;
            }
            break;
        }
        return i;
    }

    private static String trimTrailingDot(String s) {
        if (s == null) return "";
        if (s.endsWith(".")) return s.substring(0, s.length() - 1);
        return s;
    }

    private static String firstSegment(String keyOrSuffix) {
        if (keyOrSuffix == null || keyOrSuffix.isEmpty()) return null;
        int dot = keyOrSuffix.indexOf('.');
        return dot >= 0 ? keyOrSuffix.substring(0, dot) : keyOrSuffix;
    }
}
