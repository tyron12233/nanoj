package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileEvent;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileRenameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class VirtualFileSystemTest {

    private Path tempDir;
    private FileObject root;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("nanoj_vfs_test");
        root = VirtualFileSystem.getInstance().find(tempDir.toFile());
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testResolveFile() {
        FileObject fo = VirtualFileSystem.getInstance().find(tempDir.toFile());
        Assertions.assertNotNull(fo);
        Assertions.assertTrue(fo.exists());
        Assertions.assertTrue(fo.isFolder());
        Assertions.assertEquals(tempDir.toUri(), fo.toUri());
    }

    @Test
    public void testCreateAndWriteFile() throws IOException {
        String filename = "Test.txt";
        String content = "Hello World";

        FileObject file = root.createFile(filename);
        Assertions.assertTrue(file.exists());
        Assertions.assertEquals(filename, file.getName());
        Assertions.assertEquals("txt", file.getExtension());

        try (OutputStream out = file.getOutputStream()) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }

        Assertions.assertEquals(content.length(), file.getLength());
        Assertions.assertEquals(content, file.getText());
    }

    @Test
    public void testHierarchy() throws IOException {
        FileObject src = root.createFolder("src");
        FileObject main = src.createFolder("main");
        FileObject javaFile = main.createFile("Main.java");

        // Test Parent
        Assertions.assertEquals(main, javaFile.getParent());
        Assertions.assertEquals(src, main.getParent());
        Assertions.assertEquals(root, src.getParent());

        // Test Children
        List<FileObject> children = main.getChildren();
        Assertions.assertEquals(1, children.size());
        Assertions.assertEquals(javaFile, children.get(0));
    }

    @Test
    public void testDelete() throws IOException {
        FileObject file = root.createFile("ToDelete.txt");
        Assertions.assertTrue(file.exists());

        file.delete();
        Assertions.assertFalse(file.exists());
    }

    @Test
    public void testRename() throws IOException {
        FileObject file = root.createFile("OldName.txt");
        file.getOutputStream().write("data".getBytes());

        file.rename("NewName.txt");

        // The 'file' object instance still points to the old path (OldName.txt)
        // Check that old path is gone
        Assertions.assertFalse(file.exists());

        // Check new path exists
        FileObject newFile = root.getChild("NewName.txt");
        Assertions.assertTrue(newFile.exists());
        Assertions.assertEquals("data", newFile.getText());
    }

    @Test
    public void testEventPropagation() throws IOException {
        final List<String> events = new ArrayList<>();

        FileChangeListener listener = new FileChangeListener() {
            @Override public void fileCreated(FileEvent event) { events.add("CREATED: " + event.getFile().getName()); }
            @Override public void fileDeleted(FileEvent event) { events.add("DELETED: " + event.getFile().getName()); }
            @Override public void fileChanged(FileEvent event) { events.add("CHANGED: " + event.getFile().getName()); }
            @Override public void fileRenamed(FileEvent event) {
                if (event instanceof FileRenameEvent re) {
                    events.add("RENAMED: " + re.getOldFile().getName() + " -> " + re.getFile().getName());
                } else {
                    events.add("RENAMED: ? -> " + event.getFile().getName());
                }
            }
        };

        // Register global listener
        VirtualFileSystem.getInstance().addGlobalListener(listener);

        try {
            FileObject file = root.createFile("EventTest.txt");

            try (OutputStream out = file.getOutputStream()) {
                out.write("A".getBytes());
            }

            file.delete();

            // Rename should also propagate as a global event.
            FileObject renameMe = root.createFile("Old.txt");
            renameMe.rename("New.txt");

            Assertions.assertTrue(events.contains("CREATED: EventTest.txt"));
            Assertions.assertTrue(events.contains("CHANGED: EventTest.txt"));
            Assertions.assertTrue(events.contains("DELETED: EventTest.txt"));

            Assertions.assertTrue(events.contains("RENAMED: Old.txt -> New.txt"));

        } finally {
            VirtualFileSystem.getInstance().removeGlobalListener(listener);
        }
    }
    
    @Test
    public void testEquality() {
        FileObject a = VirtualFileSystem.getInstance().find(new File(tempDir.toFile(), "A.txt"));
        FileObject b = VirtualFileSystem.getInstance().find(new File(tempDir.toFile(), "A.txt"));

        Assertions.assertEquals(a, b, "Two objects pointing to same path should be equal");
        Assertions.assertEquals(a.hashCode(), b.hashCode());
    }
}