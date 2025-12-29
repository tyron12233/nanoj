package com.tyron.nanoj.lang.java.diagnostics;

import com.tyron.nanoj.api.diagnostics.DiagnosticSeverity;
import com.tyron.nanoj.api.diagnostics.ErrorHighlightingService;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.lang.java.completion.BaseJavaCompletionTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaDiagnosticsTest extends BaseJavaCompletionTest {

    @Test
    void returnsErrorsForBrokenCode() {
        String text = """
                package p;
                public class Foo {
                  void test() {
                    int x = \"notAnInt\";
                  }
                }
                """;

        FileObject file = javaFile("p.Foo", text);

        ErrorHighlightingService svc = ProjectServiceManager.getService(project, ErrorHighlightingService.class);
        var diags = svc.getDiagnostics(file, text);

        assertTrue(diags.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.ERROR), "Expected at least one ERROR");
        assertTrue(diags.stream().allMatch(d -> d.getStartOffset() >= 0 && d.getEndOffset() >= d.getStartOffset()), "Expected valid ranges");
    }
}
