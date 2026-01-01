package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class IndexManagerTest extends BaseIdeTest {

    private IndexManagerImpl indexManager;

    @BeforeEach
    public void setUp() throws IOException {
        indexManager = ((IndexManagerImpl) IndexManager.getInstance());
        indexManager.register(new SimpleWordIndexer(indexManager));
    }

    @Test
    public void testIndexingFlow() throws InterruptedException {
        MockFileObject file = new MockFileObject("/src/Hello.java", "class Hello {}");

        indexManager.processBatch(List.of(file));

        indexManager.flush();

        List<String> results = indexManager.search("word_index", "Hello");
        Assertions.assertEquals(1, results.size(), "Should find 1 result");
        Assertions.assertEquals(file.getPath(), results.get(0), "Value should be file path");
    }

    @Test
    public void testIncrementalUpdateRemovesStaleData() throws InterruptedException {
        MockFileObject file = new MockFileObject("/src/Test.java", "class OldName {}");

        indexManager.processBatch(List.of(file));
        indexManager.flush();

        Assertions.assertFalse(indexManager.search("word_index", "OldName").isEmpty());

        file.setContent("class NewName {}");
        indexManager.processBatch(List.of(file));
        indexManager.flush();

        List<String> oldResults = indexManager.search("word_index", "OldName");
        Assertions.assertTrue(oldResults.isEmpty(), "Old key should be removed");

        List<String> newResults = indexManager.search("word_index", "NewName");
        Assertions.assertEquals(1, newResults.size());
    }

    @Test
    public void testMultipleFilesSameKey() throws InterruptedException {
        MockFileObject f1 = new MockFileObject("/src/A.java", "class Shared {}");
        MockFileObject f2 = new MockFileObject("/src/B.java", "class Shared {}");

        indexManager.processBatch(List.of(f1, f2));
        indexManager.flush();

        List<String> results = indexManager.search("word_index", "Shared");
        Assertions.assertEquals(2, results.size(), results.toString());

        // Modify F1 so it no longer has "Shared"
        f1.setContent("class Modified {}");
        indexManager.processBatch(List.of(f1));
        indexManager.flush();

        results = indexManager.search("word_index", "Shared");


        Assertions.assertEquals(1, results.size(), "Should still contain B.java: " + results);
        Assertions.assertEquals("/src/B.java", results.get(0));
    }

    @Test
    public void testDirtyCheck() {
        MockFileObject f1 = file("/src/A.java", "class Shared {}");

        indexManager.processBatch(List.of(f1));
        indexManager.flush();

        indexManager.processBatch(List.of(f1));
    }

    /**
     * Test Indexer. Maps: Word -> FilePath
     */
    private static class SimpleWordIndexer implements IndexDefinition<String, String> {
        private final IndexManager indexManager;

        public SimpleWordIndexer(IndexManager indexManager) {
            this.indexManager = indexManager;
        }

        @Override public String id() { return "word_index"; }
        @Override public int getVersion() { return 1; }
        @Override public boolean supports(FileObject fileObject) { return true; }

        @Override
        public Map<String, String> map(FileObject file, Object inputData) {
            Map<String, String> map = new HashMap<>();
            try {
                String text = file.getText();
                String[] tokens = text.split("\\s+");
                for (int i = 0; i < tokens.length - 1; i++) {
                    if (tokens[i].equals("class")) {
                        map.put(tokens[i+1], file.getPath());
                    }
                }
            } catch (IOException e) { e.printStackTrace(); }
            return map;
        }

        @Override
        public boolean isValueForFile(String value, int fileId) {
            return true;
        }

        @Override public byte[] serializeKey(String key) { return key.getBytes(StandardCharsets.UTF_8); }
        @Override public byte[] serializeValue(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public String deserializeKey(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
        @Override public String deserializeValue(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
    }
}