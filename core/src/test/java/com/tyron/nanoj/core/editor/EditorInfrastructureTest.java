package com.tyron.nanoj.core.editor;

import com.tyron.nanoj.api.editor.Document;
import com.tyron.nanoj.api.editor.Editor;
import com.tyron.nanoj.api.editor.EditorManager;
import com.tyron.nanoj.api.editor.FileDocumentManager;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.vfs.LocalFileSystem;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.testFramework.BaseEditorTest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class EditorInfrastructureTest extends BaseEditorTest {

    @Override
    protected void beforeEach() throws Exception {
        super.beforeEach();

        VirtualFileManager.getInstance().register(LocalFileSystem.getInstance());
    }

    @Test
    public void editsStayInMemoryUntilCommit() throws Exception {
        File javaFile = new File(temporaryFolder, "Main.java");
        Files.writeString(javaFile.toPath(), "class Main {}\n");

        FileObject fileObject = VirtualFileManager.getInstance().find(javaFile);

        FileDocumentManager fdm = FileDocumentManagerImpl.getInstance(project);
        EditorManager em = EditorManagerImpl.getInstance(project);

        Document doc = fdm.getDocument(fileObject);
        assertEquals("class Main {}\n", doc.getText());
        assertFalse(fdm.isModified(doc));

        // Edit in memory.
        doc.replace(0, doc.getTextLength(), "class Main { int x; }\n");
        assertTrue(fdm.isModified(doc));

        // Disk content unchanged.
        assertEquals("class Main {}\n", Files.readString(javaFile.toPath()));

        // In-memory file view reflects unsaved text.
        assertEquals("class Main { int x; }\n", fdm.getInMemoryView(fileObject).getText());

        // Commit persists.
        fdm.commitDocument(doc);
        assertFalse(fdm.isModified(doc));
        assertEquals("class Main { int x; }\n", Files.readString(javaFile.toPath()));

        // Editor lifecycle.
        Editor editor = em.openEditor(fileObject);
        assertSame(doc, editor.getDocument());
        assertEquals(1, em.getEditors(doc).size());
        em.releaseEditor(editor);
        assertEquals(0, em.getEditors(doc).size());
    }
}
