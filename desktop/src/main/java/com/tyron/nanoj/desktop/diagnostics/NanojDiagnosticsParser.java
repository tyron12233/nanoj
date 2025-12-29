package com.tyron.nanoj.desktop.diagnostics;

import com.tyron.nanoj.api.diagnostics.Diagnostic;
import com.tyron.nanoj.api.diagnostics.DiagnosticSeverity;
import com.tyron.nanoj.api.diagnostics.ErrorHighlightingService;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;

import javax.swing.text.BadLocationException;
import java.util.List;
import java.util.Objects;

/**
 * Bridges Nanoj's error-highlighting diagnostics into RSyntaxTextArea's parser/notice system.
 */
public final class NanojDiagnosticsParser extends AbstractParser {

    private final Project project;
    private final FileObject file;
    private final RSyntaxTextArea textArea;

    public NanojDiagnosticsParser(Project project, FileObject file, RSyntaxTextArea textArea) {
        this.project = Objects.requireNonNull(project, "project");
        this.file = Objects.requireNonNull(file, "file");
        this.textArea = Objects.requireNonNull(textArea, "textArea");
    }

    @Override
    public ParseResult parse(RSyntaxDocument doc, String style) {
        DefaultParseResult result = new DefaultParseResult(this);

        String text;
        try {
            text = doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            result.setError(e);
            return result;
        }

        ErrorHighlightingService svc = ProjectServiceManager.getService(project, ErrorHighlightingService.class);
        List<Diagnostic> diags;
        try {
            diags = svc.getDiagnostics(file, text);
        } catch (Throwable t) {
            result.setError(t instanceof Exception ex ? ex : new RuntimeException(t));
            return result;
        }

        for (Diagnostic d : diags) {
            if (d == null) continue;

            int start = Math.max(0, Math.min(d.getStartOffset(), text.length()));
            int end = Math.max(0, Math.min(d.getEndOffset(), text.length()));
            if (end < start) end = start;
            int len = Math.max(1, end - start);

            int line;
            try {
                line = textArea.getLineOfOffset(start);
            } catch (BadLocationException e) {
                line = 0;
            }

            DefaultParserNotice notice = new DefaultParserNotice(this, d.getMessage(), line, start, len);
            notice.setLevel(toLevel(d.getSeverity()));
            notice.setShowInEditor(true);
            result.addNotice(notice);
        }

        return result;
    }

    private static ParserNotice.Level toLevel(DiagnosticSeverity s) {
        if (s == null) return ParserNotice.Level.INFO;
        return switch (s) {
            case ERROR -> ParserNotice.Level.ERROR;
            case WARNING -> ParserNotice.Level.WARNING;
            case INFO -> ParserNotice.Level.INFO;
        };
    }
}
