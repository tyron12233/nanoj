package com.tyron.nanoj.api.diagnostics;

import com.tyron.nanoj.api.vfs.FileObject;

import java.util.List;

/**
 * Language-specific diagnostics provider for a particular file.
 *
 * Implementations should treat the passed text as the source of truth (it may be unsaved).
 */
public interface DiagnosticsProvider {

    /**
     * Computes diagnostics for the given file and text snapshot.
     *
     * This may be expensive and should not be executed on a UI thread.
     */
    List<Diagnostic> getDiagnostics(FileObject file, String text);
}
