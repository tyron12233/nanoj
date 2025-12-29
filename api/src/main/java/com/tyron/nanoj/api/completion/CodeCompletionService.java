package com.tyron.nanoj.api.completion;

import com.tyron.nanoj.api.vfs.FileObject;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Project-scoped facade over language completion providers.
 *
 * This service aggregates completions for a given file/text/offset and returns {@link LookupElement}s.
 *
 * Threading:
 * - {@link #getCompletions(FileObject, String, int)} may be expensive (index/analysis) and MUST NOT be called
 *   on a UI thread.
 * - Callers should execute it on a background thread and then marshal UI updates back to the UI thread.
 *
 * Dumb mode:
 * - While the project is in dumb mode (see {@code com.tyron.nanoj.api.dumb.DumbService}), implementations should
 *   avoid invoking non-dumb-aware providers. Callers may receive an empty list until indexing completes.
 */
public interface CodeCompletionService {

    List<LookupElement> getCompletions(FileObject file, String text, int offset);

    /**
     * Convenience async wrapper around {@link #getCompletions(FileObject, String, int)}.
     */
    default CompletableFuture<List<LookupElement>> getCompletionsAsync(FileObject file, String text, int offset, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.supplyAsync(() -> getCompletions(file, text, offset), executor);
    }

    /**
     * Convenience async wrapper using {@link ForkJoinPool#commonPool()}.
     */
    default CompletableFuture<List<LookupElement>> getCompletionsAsync(FileObject file, String text, int offset) {
        return CompletableFuture.supplyAsync(() -> getCompletions(file, text, offset));
    }
}
