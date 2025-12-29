package com.tyron.nanoj.api.completion;

import org.jetbrains.annotations.NotNull;

/**
 * Strategy for matching a typed prefix against a candidate string.
 */
public abstract class PrefixMatcher {

    protected final String prefix;

    protected PrefixMatcher(String prefix) {
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * @return true if the name matches the prefix (e.g. "Str" matches "String").
     */
    public abstract boolean prefixMatches(@NotNull String name);

    /**
     * @return A new matcher with a different prefix.
     */
    public abstract PrefixMatcher cloneWithPrefix(@NotNull String prefix);
    
    public static class Plain extends PrefixMatcher {
        public Plain(String prefix) { super(prefix); }

        @Override
        public boolean prefixMatches(@NotNull String name) {
            if (prefix.isEmpty()) return true;
            return name.toLowerCase().startsWith(prefix.toLowerCase());
        }

        @Override
        public PrefixMatcher cloneWithPrefix(@NotNull String prefix) {
            return new Plain(prefix);
        }
    }
}