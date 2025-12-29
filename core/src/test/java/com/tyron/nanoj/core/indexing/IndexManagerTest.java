package com.tyron.nanoj.core.indexing;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class IndexManagerTest {

    private File cacheDir;
    private Project mockProject;
    private IndexManager indexManager;

    @BeforeEach
    public void setUp() throws IOException {
        cacheDir = Files.createTempDirectory("ristretto_cache").toFile();

        mockProject = new Project() {
            @Override public File getCacheDir() { return cacheDir; }
            // Stub other methods...
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

        // Pass the manager to the indexer so it can verify file ownership
        indexManager.register(new SimpleWordIndexer(indexManager));
    }

    @AfterEach
    public void tearDown() {
        indexManager.dispose();
        deleteRecursive(cacheDir);
    }

    @Test
    public void testIndexingFlow() throws InterruptedException {
        MockFileObject file = new MockFileObject("/src/Hello.java", "class Hello {}");

        indexManager.updateFile(file);

        indexManager.flush();

        List<String> results = indexManager.search("word_index", "Hello");
        Assertions.assertEquals(1, results.size(), "Should find 1 result");
        Assertions.assertEquals(file.getPath(), results.get(0), "Value should be file path");
    }

    @Test
    public void testIncrementalUpdateRemovesStaleData() throws InterruptedException {
        MockFileObject file = new MockFileObject("/src/Test.java", "class OldName {}");

        indexManager.updateFile(file);
        indexManager.flush();

        Assertions.assertFalse(indexManager.search("word_index", "OldName").isEmpty());

        file.setContent("class NewName {}");
        indexManager.updateFile(file);
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

        indexManager.updateFile(f1);
        indexManager.updateFile(f2);
        indexManager.flush();

        List<String> results = indexManager.search("word_index", "Shared");
        Assertions.assertEquals(2, results.size(), results.toString());

        // Modify F1 so it no longer has "Shared"
        f1.setContent("class Modified {}");
        indexManager.updateFile(f1);
        indexManager.flush();

        results = indexManager.search("word_index", "Shared");


        Assertions.assertEquals(1, results.size(), "Should still contain B.java: " + results);
        Assertions.assertEquals("/src/B.java", results.get(0));
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) for (File c : Objects.requireNonNull(file.listFiles())) deleteRecursive(c);
        file.delete();
    }

    /**
     * Test Indexer. Maps: Word -> FilePath
     */
    private static class SimpleWordIndexer implements IndexDefinition<String, String> {
        private final IndexManager indexManager;

        public SimpleWordIndexer(IndexManager indexManager) {
            this.indexManager = indexManager;
        }

        @Override public String getId() { return "word_index"; }
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
            // FIX: Retrieve the actual path associated with the fileId being cleaned
            String pathForId = indexManager.getFilePath(fileId);

            // Return true ONLY if the value (which is a path) matches the file being cleaned.
            // This prevents deleting B.java's entry when cleaning A.java.
            return value.equals(pathForId);
        }

        @Override public byte[] serializeKey(String key) { return key.getBytes(StandardCharsets.UTF_8); }
        @Override public byte[] serializeValue(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public String deserializeKey(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
        @Override public String deserializeValue(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
    }
}