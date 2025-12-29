package com.tyron.nanoj.api.diagnostics;

import java.util.Objects;

/**
 * A single diagnostic (error/warning/info) produced for a specific text snapshot.
 *
 * Offsets are 0-based character offsets into the provided text.
 *
 * Ranges follow the same convention as {@code Document.replace(start, end, ...)}: [startOffset, endOffset).
 */
public final class Diagnostic {

    private final DiagnosticSeverity severity;
    private final int startOffset;
    private final int endOffset;
    private final String message;

    // Optional metadata
    private final String code;
    private final String source;

    public Diagnostic(
            DiagnosticSeverity severity,
            int startOffset,
            int endOffset,
            String message,
            String code,
            String source
    ) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.message = message != null ? message : "";
        this.code = code;
        this.source = source;
    }

    public DiagnosticSeverity getSeverity() {
        return severity;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Optional diagnostic code, if the underlying engine provides one.
     */
    public String getCode() {
        return code;
    }

    /**
     * Optional source id (e.g. "javac").
     */
    public String getSource() {
        return source;
    }
}
