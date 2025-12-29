package com.tyron.nanoj.api.completion;

import org.jetbrains.annotations.NotNull;

/**
 * Weighs completion items for sorting.
 * <p>
 * Modeled after IntelliJ's LookupElementWeigher concept.
 * Higher weight means the item should appear earlier.
 */
public interface LookupElementWeigher {

    /**
     * Stable identifier for debugging/ordering.
     */
    @NotNull
    String id();

    /**
     * Computes the weight for this element.
     *
     * @return higher values sort first.
     */
    int weigh(@NotNull CompletionParameters parameters, @NotNull LookupElement element);
}
