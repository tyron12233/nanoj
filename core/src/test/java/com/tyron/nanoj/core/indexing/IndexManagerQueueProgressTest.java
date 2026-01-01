package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.api.indexing.IndexingProgressSnapshot;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.testFramework.BaseIdeTest;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class IndexManagerQueueProgressTest extends BaseIdeTest {

    IndexManagerImpl indexManager;

    @BeforeEach
    public void setUp() {
        indexManager = (IndexManagerImpl) IndexManager.getInstance();
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

        indexManager.processBatch(List.of(new MockFileObject("/src/A.java", "class A {}")));
        indexManager.processBatch(List.of(new MockFileObject("/src/B.java", "class B {}")));
        indexManager.processBatch(List.of(new MockFileObject("/src/C.java", "class C {}")));

        indexManager.flush();

        Assertions.assertTrue(sawBusy.get(), "Expected at least one busy progress update");

        IndexingProgressSnapshot end = last.get();
        Assertions.assertNotNull(end, "Expected final progress snapshot");
        Assertions.assertEquals(0, end.getQueuedFiles());
        Assertions.assertEquals(0, end.getRunningFiles());
//        Assertions.assertNull(end.getCurrentFilePath());
        Assertions.assertTrue(end.getCompletedFiles() >= 3);
    }

    private record RecordingIndexer(List<String> seen) implements IndexDefinition<String, String> {

        @Override
            public String id() {
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
