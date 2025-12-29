package com.tyron.nanoj.api.completion;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Extension Point. Implementations provide completion items.
 */
public interface CompletionProvider {

    /**
     * The main method to implement.
     *
     * @param parameters Context (file, offset, project).
     * @param result     The collector where you add items.
     */
    public abstract void addCompletions(@NotNull CompletionParameters parameters,
                                        @NotNull CompletionResultSet result);
}