package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.indexing.IndexingProgressSnapshot;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.test.MockFileObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class IndexManagerQueueProgressTest {

    private File cacheDir;
    private Project mockProject;
    private IndexManager indexManager;

    @BeforeEach
    public void setUp() throws IOException {
        cacheDir = Files.createTempDirectory("nanoj_index_queue_test").toFile();

        mockProject = new Project() {
            @Override public File getCacheDir() { return cacheDir; }
            @Override public String getName() { return ""; }
            @Override public FileObject getRootDirectory() { return null; }
            @Override public List<FileObject> getSourceRoots() { return List.of(); }
            @Override public List<FileObject> getResourceRoots() { return List.of(); }
            @Override public List<FileObject> getClassPath() { return List.of(); }
            @Override public List<FileObject> getBootClassPath() { return List.of(); }
            @Override public FileObject getBuildDirectory() { return null; }
            @Override public ProjectConfiguration getConfiguration() { return null; }
            @Override public boolean isOpen() { return true; }
            @Override public void dispose() {}
        };

        indexManager = new IndexManager(mockProject);
    }

    @AfterEach
    public void tearDown() {
        indexManager.dispose();
        deleteRecursive(cacheDir);
    }

    @Test
    public void dedupCoalescesRequestsAndIndexesLatest() {
        List<String> seenContents = Collections.synchronizedList(new ArrayList<>());
        indexManager.register(new RecordingIndexer(seenContents));

        MockFileObject file = new MockFileObject("/src/A.java", "class A {}");

        Future<?> f1 = indexManager.updateFileAsync(file);

        // Update the same path again quickly; depending on timing this may cause either a coalesced run
        // or a rerun, but the final indexed content must be the latest.
        file.setContent("class B {}");
        Future<?> f2 = indexManager.updateFileAsync(file);

        Assertions.assertSame(f1, f2, "Expected same Future for repeated requests of the same file");

        indexManager.flush();

        Assertions.assertFalse(seenContents.isEmpty(), "Expected at least one indexing run");
        Assertions.assertEquals("class B {}", seenContents.get(seenContents.size() - 1));
        Assertions.assertTrue(seenContents.size() <= 2, "Expected at most one rerun for the same file");

        IndexingProgressSnapshot snapshot = indexManager.getProgressSnapshot();
        Assertions.assertEquals(0, snapshot.getQueuedFiles());
        Assertions.assertEquals(0, snapshot.getRunningFiles());
        Assertions.assertNull(snapshot.getCurrentFilePath());
    }

    @Test
    public void progressListenerSeesActivityAndDrain() {
        indexManager.register(new RecordingIndexer(Collections.synchronizedList(new ArrayList<>())));

        AtomicBoolean sawBusy = new AtomicBoolean(false);
        AtomicReference<IndexingProgressSnapshot> last = new AtomicReference<>();

        indexManager.addProgressListener(snapshot -> {
            last.set(snapshot);
            if (snapshot.getQueuedFiles() > 0 || snapshot.getRunningFiles() > 0) {
                sawBusy.set(true);
            }
        });

        indexManager.updateFileAsync(new MockFileObject("/src/A.java", "class A {}"));
        indexManager.updateFileAsync(new MockFileObject("/src/B.java", "class B {}"));
        indexManager.updateFileAsync(new MockFileObject("/src/C.java", "class C {}"));

        indexManager.flush();

        Assertions.assertTrue(sawBusy.get(), "Expected at least one busy progress update");

        IndexingProgressSnapshot end = last.get();
        Assertions.assertNotNull(end, "Expected final progress snapshot");
        Assertions.assertEquals(0, end.getQueuedFiles());
        Assertions.assertEquals(0, end.getRunningFiles());
        Assertions.assertNull(end.getCurrentFilePath());
        Assertions.assertTrue(end.getCompletedFiles() >= 3);
    }

    private void deleteRecursive(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            for (File c : Objects.requireNonNull(file.listFiles())) {
                deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class RecordingIndexer implements IndexDefinition<String, String> {
        private final List<String> seen;

        private RecordingIndexer(List<String> seen) {
            this.seen = seen;
        }

        @Override
        public String getId() {
            return "recording_index";
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public boolean supports(FileObject fileObject) {
            return true;
        }

        @Override
        public Map<String, String> map(FileObject file, Object inputData) {
            try {
                seen.add(file.getText());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new HashMap<>();
        }

        @Override
        public boolean isValueForFile(String value, int fileId) {
            return true;
        }

        @Override
        public byte[] serializeKey(String key) {
            return key.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] serializeValue(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserializeKey(byte[] data) {
            return new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public String deserializeValue(byte[] data) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }
}
