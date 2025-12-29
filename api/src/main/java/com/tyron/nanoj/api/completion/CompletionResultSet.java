package com.tyron.nanoj.api.completion;

import org.jetbrains.annotations.NotNull;

/**
 * A collector for completion items.
 * <p>
 * Providers receive this object and feed elements into it.
 * It handles filtering (prefix matching) automatically.
 * </p>
 */
public interface CompletionResultSet {

    /**
     * Adds an element to the results.
     * The element is only actually added if it matches the current prefix.
     */
    void addElement(@NotNull LookupElement element);

    /**
     * Adds a batch of elements.
     */
    void addAllElements(@NotNull Iterable<? extends LookupElement> elements);

    /**
     * Creates a new result set with a different prefix.
     * Use this if the completion logic changes (e.g. after a dot '.').
     */
    @NotNull
    CompletionResultSet withPrefixMatcher(@NotNull PrefixMatcher matcher);
    
    @NotNull
    CompletionResultSet withPrefixMatcher(@NotNull String prefix);

    /**
     * Checks if the completion session has been cancelled.
     * Providers should check this in loops to stop work early.
     */
    boolean isStopped();
    
    /**
     * Call this to stop processing further providers (if you found an exact match).
     */
    void stopHere();

    @NotNull
    PrefixMatcher getPrefixMatcher();
}