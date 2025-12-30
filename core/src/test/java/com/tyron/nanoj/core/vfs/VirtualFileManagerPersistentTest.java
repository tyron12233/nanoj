package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileEvent;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileRenameEvent;
import com.tyron.nanoj.testFramework.TestApplication;
import com.tyron.nanoj.core.service.ApplicationServiceManager;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class VirtualFileManagerPersistentTest {

    private Path tempDir;
    private FileObject root;
    private com.tyron.nanoj.api.vfs.VirtualFileManager vfm;

    @BeforeEach
    public void setUp() throws IOException {
        TestApplication.install();

        // Ensure the default core implementation is used for this test
        // (TestVirtualFileManager doesn't do snapshot syncing).
        ApplicationServiceManager.registerInstance(VirtualFileManager.class, new VirtualFileManagerImpl());

        vfm = com.tyron.nanoj.api.vfs.VirtualFileManager.getInstance();
        vfm.clear();

        tempDir = Files.createTempDirectory("nanoj_vfs_persistent_test");
        root = vfm.find(tempDir.toFile());
        vfm.trackRoot(root);
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
    public void testFileIdStableAcrossRename() throws IOException {
        FileObject a = root.createFile("a.txt");
        int idA = vfm.getFileId(a);

        a.rename("b.txt");
        FileObject b = root.getChild("b.txt");
        int idB = vfm.getFileId(b);

        Assertions.assertEquals(idA, idB);
    }

    @Test
    public void testRefreshSyncDetectsExternalChanges() throws IOException {
        List<String> events = new ArrayList<>();

        FileChangeListener listener = new FileChangeListener() {
            @Override
            public void fileCreated(FileEvent event) {
                events.add("CREATED:" + event.getFile().getName());
            }

            @Override
            public void fileDeleted(FileEvent event) {
                events.add("DELETED:" + event.getFile().getName());
            }

            @Override
            public void fileChanged(FileEvent event) {
                events.add("CHANGED:" + event.getFile().getName());
            }

            @Override
            public void fileRenamed(FileEvent event) {
                if (event instanceof FileRenameEvent re) {
                    events.add("RENAMED:" + re.getOldFile().getName() + "->" + re.getFile().getName());
                } else {
                    events.add("RENAMED:?->" + event.getFile().getName());
                }
            }
        };

        vfm.addGlobalListener(listener);
        try {
            Path p = tempDir.resolve("ext.txt");
            Files.writeString(p, "a");
            vfm.refreshAll(false);
            Assertions.assertTrue(events.contains("CREATED:ext.txt"), String.valueOf(events));

            events.clear();
            Files.writeString(p, "bb");
            vfm.refreshAll(false);
            Assertions.assertTrue(events.contains("CHANGED:ext.txt"), String.valueOf(events));

            events.clear();
            Path q = tempDir.resolve("moved.txt");
            Files.move(p, q);
            vfm.refreshAll(false);

            // Best-effort: on platforms without stable file keys, this may appear as delete+create.
            boolean sawRename = events.stream().anyMatch(s -> s.startsWith("RENAMED:"));
            boolean sawDeleteCreate = events.contains("DELETED:ext.txt") && events.contains("CREATED:moved.txt");
            Assertions.assertTrue(sawRename || sawDeleteCreate, String.valueOf(events));

            events.clear();
            Files.delete(q);
            vfm.refreshAll(false);
            Assertions.assertTrue(events.contains("DELETED:moved.txt"), String.valueOf(events));
        } finally {
            vfm.removeGlobalListener(listener);
        }
    }
}
