package com.tyron.nanoj.core.dumb;

import com.tyron.nanoj.api.completion.CodeCompletionService;
import com.tyron.nanoj.api.completion.CompletionProvider;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementBuilder;
import com.tyron.nanoj.api.dumb.DumbAware;
import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.editor.SyntaxHighlighter;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.testFramework.BaseCompletionTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DumbModeCompletionTest extends BaseCompletionTest {

    @Test
    public void smartOnlyProvidersAreSkippedWhileDumb() {
        ProjectServiceManager.registerExtension(project, LanguageSupport.class, SmartOnlyLanguageSupport.class);
        ProjectServiceManager.registerExtension(project, LanguageSupport.class, DumbAwareLanguageSupport.class);

        FileObject smartFile = file("a.smart", "");
        FileObject dumbFile = file("a.dumb", "");

        CodeCompletionService service = ProjectServiceManager.getService(project, CodeCompletionService.class);
        DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);

        // Smart mode: both providers work for their file types.
        assertHasLookup(service.getCompletions(smartFile, "", 0), "smartItem");
        assertHasLookup(service.getCompletions(dumbFile, "", 0), "dumbItem");

        // Dumb mode: only DumbAware providers should run.
        try (DumbService.DumbModeToken ignored = dumbService.startDumbTask("test")) {
            assertTrue(dumbService.isDumb());
            assertTrue(service.getCompletions(smartFile, "", 0).isEmpty());
            assertHasLookup(service.getCompletions(dumbFile, "", 0), "dumbItem");
        }

        assertFalse(dumbService.isDumb());
        assertHasLookup(service.getCompletions(smartFile, "", 0), "smartItem");
    }

    private static void assertHasLookup(List<LookupElement> items, String lookupString) {
        for (LookupElement e : items) {
            if (lookupString.equals(e.getLookupString())) {
                return;
            }
        }
        fail("Expected lookup '" + lookupString + "' but got: " + items.stream().map(LookupElement::getLookupString).toList());
    }

    public static final class SmartOnlyLanguageSupport implements LanguageSupport {
        public SmartOnlyLanguageSupport(Project project) {
        }

        @Override
        public boolean canHandle(FileObject file) {
            return file.getPath().endsWith(".smart");
        }

        @Override
        public SyntaxHighlighter createHighlighter(Project project, FileObject file) {
            return content -> java.util.concurrent.CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionProvider createCompletionProvider(Project project, FileObject file) {
            return (parameters, result) -> result.addElement(LookupElementBuilder.create("smartItem"));
        }
    }

    public static final class DumbAwareLanguageSupport implements LanguageSupport {
        public DumbAwareLanguageSupport(Project project) {
        }

        @Override
        public boolean canHandle(FileObject file) {
            return file.getPath().endsWith(".dumb");
        }

        @Override
        public SyntaxHighlighter createHighlighter(Project project, FileObject file) {
            return content -> java.util.concurrent.CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionProvider createCompletionProvider(Project project, FileObject file) {
            return new DumbAwareProvider();
        }
    }

    public static final class DumbAwareProvider implements CompletionProvider, DumbAware {
        @Override
        public void addCompletions(com.tyron.nanoj.api.completion.CompletionParameters parameters,
                                  com.tyron.nanoj.api.completion.CompletionResultSet result) {
            result.addElement(LookupElementBuilder.create("dumbItem"));
        }
    }
}
