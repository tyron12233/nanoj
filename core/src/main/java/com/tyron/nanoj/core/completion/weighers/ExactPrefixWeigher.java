package com.tyron.nanoj.core.completion.weighers;

import com.tyron.nanoj.api.completion.CompletionParameters;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementWeigher;
import com.tyron.nanoj.api.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Prefers items that match the currently typed identifier prefix more closely.
 */
public final class ExactPrefixWeigher implements LookupElementWeigher {

    public ExactPrefixWeigher(Project project) {
    }

    @Override
    public @NotNull String id() {
        return "exactPrefix";
    }

    @Override
    public int weigh(@NotNull CompletionParameters parameters, @NotNull LookupElement element) {
        String text = parameters.text();
        int offset = parameters.offset();
        if (text == null || offset <= 0) {
            return 0;
        }

        String prefix = currentIdentifierPrefix(text, Math.min(offset, text.length()));
        if (prefix.isEmpty()) {
            return 0;
        }

        String lookup = element.getLookupString();
        if (lookup == null) {
            return 0;
        }

        if (lookup.equals(prefix)) {
            return 200;
        }
        if (lookup.startsWith(prefix)) {
            return 100;
        }
        if (lookup.toLowerCase().startsWith(prefix.toLowerCase())) {
            return 50;
        }
        return 0;
    }

    private static String currentIdentifierPrefix(String text, int offset) {
        int i = offset - 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (!Character.isJavaIdentifierPart(c)) {
                break;
            }
            i--;
        }
        return text.substring(i + 1, offset);
    }
}
