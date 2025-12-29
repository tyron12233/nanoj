package com.tyron.nanoj.api.diagnostics;

import com.tyron.nanoj.api.vfs.FileObject;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Project-scoped facade over language diagnostics providers.
 *
 * This is used for "error highlighting": producing errors/warnings/info with precise ranges.
 * This is NOT syntax highlighting.
 *
 * Threading:
 * - {@link #getDiagnostics(FileObject, String)} may be expensive and MUST NOT be called on a UI thread.
 */
public interface ErrorHighlightingService {

    List<Diagnostic> getDiagnostics(FileObject file, String text);

    default CompletableFuture<List<Diagnostic>> getDiagnosticsAsync(FileObject file, String text, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.supplyAsync(() -> getDiagnostics(file, text), executor);
    }

    default CompletableFuture<List<Diagnostic>> getDiagnosticsAsync(FileObject file, String text) {
        return CompletableFuture.supplyAsync(() -> getDiagnostics(file, text), ForkJoinPool.commonPool());
    }
}
