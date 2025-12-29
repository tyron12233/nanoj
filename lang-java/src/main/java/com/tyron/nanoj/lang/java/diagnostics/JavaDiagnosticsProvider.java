package com.tyron.nanoj.lang.java.diagnostics;

import com.tyron.nanoj.api.diagnostics.Diagnostic;
import com.tyron.nanoj.api.diagnostics.DiagnosticSeverity;
import com.tyron.nanoj.api.diagnostics.DiagnosticsProvider;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.lang.java.source.JavaSource;

import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Java diagnostics backed by javac diagnostics from {@link com.tyron.nanoj.lang.java.source.JavaSource}.
 */
public final class JavaDiagnosticsProvider implements DiagnosticsProvider {

    private final Project project;

    public JavaDiagnosticsProvider(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    @Override
    public List<Diagnostic> getDiagnostics(FileObject file, String text) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(text, "text");

        JavaSource source = JavaSource.forFile(project, file);

        return source.runDiagnosticsTask(text, info -> {
            List<javax.tools.Diagnostic<? extends JavaFileObject>> ds = info.getDiagnostics();
            if (ds == null || ds.isEmpty()) {
                return List.<Diagnostic>of();
            }

            List<Diagnostic> out = new ArrayList<>(ds.size());
            for (javax.tools.Diagnostic<? extends JavaFileObject> d : ds) {
                if (d == null) continue;

                DiagnosticSeverity severity = toSeverity(d.getKind());

                int start = (int) safePos(d.getStartPosition());
                int end = (int) safePos(d.getEndPosition());

                if (start < 0 || end < 0 || end < start) {
                    int pos = (int) safePos(d.getPosition());
                    if (pos >= 0) {
                        start = pos;
                        end = pos + 1;
                    } else {
                        start = 0;
                        end = 0;
                    }
                }

                // Clamp to snapshot.
                int len = text.length();
                start = clamp(start, 0, len);
                end = clamp(end, 0, len);
                if (end < start) end = start;
                if (start == end && start < len) end = start + 1;

                String message;
                try {
                    message = d.getMessage(null);
                } catch (Throwable t) {
                    message = String.valueOf(d);
                }

                out.add(new Diagnostic(severity, start, end, message, null, "javac"));
            }

            return out;
        }).join();
    }

    private static long safePos(long pos) {
        return pos == javax.tools.Diagnostic.NOPOS ? -1 : pos;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static DiagnosticSeverity toSeverity(Kind kind) {
        if (kind == null) return DiagnosticSeverity.INFO;
        return switch (kind) {
            case ERROR -> DiagnosticSeverity.ERROR;
            case WARNING, MANDATORY_WARNING -> DiagnosticSeverity.WARNING;
            case NOTE, OTHER -> DiagnosticSeverity.INFO;
        };
    }
}
