package com.tyron.nanoj.core.completion;

import com.tyron.nanoj.api.completion.AutoPopupController;
import com.tyron.nanoj.api.completion.CompletionEvent;
import com.tyron.nanoj.api.completion.CompletionProvider;
import com.tyron.nanoj.api.completion.LookupElementBuilder;
import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.editor.SyntaxHighlighter;
import com.tyron.nanoj.api.language.LanguageSupport;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.editor.EditorManagerImpl;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.vfs.VirtualFileSystem;
import com.tyron.nanoj.testFramework.BaseCompletionTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class AutoPopupCompletionTest extends BaseCompletionTest {

    @Override
    protected void registerProjectServices() {
        super.registerProjectServices();

        ProjectServiceManager.registerExtension(project, LanguageSupport.class, TestLanguage.class);
    }

    @Test
    public void triggersCompletionOnDotInsertion() throws Exception {
        var fileObject = file("Main.java", "class Main {}\n");

        Editor editor = EditorManagerImpl.getInstance(project).openEditor(fileObject);
        AutoPopupController controller = AutoPopupControllerImpl.getInstance(project);

        AtomicReference<CompletionEvent> lastEvent = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        var handle = controller.attach(editor, e -> {
            lastEvent.set(e);
            latch.countDown();
        });

        editor.getCaretModel().moveToOffset(editor.getDocument().getTextLength());
        editor.getDocument().insertString(editor.getCaretModel().getOffset(), ".");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected auto-popup completion callback");
        assertNotNull(lastEvent.get());
        assertEquals('.', lastEvent.get().getTriggerChar());
        assertEquals(1, lastEvent.get().getItems().size());
        assertEquals("length", lastEvent.get().getItems().get(0).getLookupString());

        handle.dispose();
    }

    @Test
    public void triggersCompletionOnSpaceAfterNew() throws Exception {
        var fileObject = file("Main.java", "new");

        Editor editor = EditorManagerImpl.getInstance(project).openEditor(fileObject);
        AutoPopupController controller = AutoPopupControllerImpl.getInstance(project);

        AtomicReference<CompletionEvent> lastEvent = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        var handle = controller.attach(editor, e -> {
            lastEvent.set(e);
            latch.countDown();
        });

        editor.getCaretModel().moveToOffset(editor.getDocument().getTextLength());
        editor.getDocument().insertString(editor.getCaretModel().getOffset(), " ");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected auto-popup completion callback");
        assertNotNull(lastEvent.get());
        assertEquals(' ', lastEvent.get().getTriggerChar());

        handle.dispose();
    }

    @Test
    public void doesNotTriggerCompletionOnArbitrarySpace() throws Exception {
        var fileObject = file("Main.java", "class Main {}\n");

        Editor editor = EditorManagerImpl.getInstance(project).openEditor(fileObject);
        AutoPopupController controller = AutoPopupControllerImpl.getInstance(project);

        CountDownLatch latch = new CountDownLatch(1);
        var handle = controller.attach(editor, e -> latch.countDown());

        editor.getCaretModel().moveToOffset(editor.getDocument().getTextLength());
        editor.getDocument().insertString(editor.getCaretModel().getOffset(), " ");

        assertFalse(latch.await(300, TimeUnit.MILLISECONDS), "Did not expect auto-popup completion callback");

        handle.dispose();
    }

    /**
     * Minimal language support for tests.
     */
    public static class TestLanguage implements LanguageSupport {

        public TestLanguage(com.tyron.nanoj.api.project.Project project) {
        }

        @Override
        public boolean canHandle(FileObject file) {
            return true;
        }

        @Override
        public SyntaxHighlighter createHighlighter(com.tyron.nanoj.api.project.Project project, FileObject file) {
            return content -> java.util.concurrent.CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionProvider createCompletionProvider(com.tyron.nanoj.api.project.Project project, FileObject file) {
            return (parameters, result) -> result.addElement(LookupElementBuilder.create("length"));
        }
    }
}
