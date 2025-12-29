package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class IndexManagerNewDefinitionBackfillTest extends BaseIdeTest {

    @Test
    public void registeringNewIndexBackfillsOnlyNewDefinition() throws Exception {
        IndexManager indexManager = IndexManager.getInstance(project);

        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();

        indexManager.register(new CountingWordIndexer(indexManager, "word_index", oldCalls));

        MockFileObject file = file("src/Hello.java", "class Hello {}");
        indexManager.updateFile(file);
        indexManager.flush();

        Assertions.assertEquals(1, indexManager.search("word_index", "Hello").size());
        int callsAfterInitialIndexing = oldCalls.get();
        Assertions.assertTrue(callsAfterInitialIndexing > 0, "sanity: first index should have run");

        // Simulate "app update": a new index definition is added after files were already indexed.
        indexManager.register(new ExtensionIndexer("ext_index", newCalls));

        indexManager.updateFile(file);
        // Indexing inputs are submitted asynchronously; poll until the new index appears.
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadlineNs) {
            indexManager.flush();
            List<String> ext = indexManager.search("ext_index", "java");
            if (!ext.isEmpty()) {
                break;
            }
            Thread.sleep(25);
        }

        List<String> extResults = indexManager.search("ext_index", "java");
        Assertions.assertEquals(List.of(file.getPath()), extResults);

        // Critically: old definition should NOT be re-run as part of the backfill.
        Assertions.assertTrue(callsAfterInitialIndexing < oldCalls.get(), "existing indices should not be re-indexed during new-index backfill");
        Assertions.assertTrue(newCalls.get() > 0, "new index should have been backfilled");
    }

    private static final class CountingWordIndexer implements IndexDefinition<String, String> {
        private final IndexManager indexManager;
        private final String id;
        private final AtomicInteger calls;

        private CountingWordIndexer(IndexManager indexManager, String id, AtomicInteger calls) {
            this.indexManager = indexManager;
            this.id = id;
            this.calls = calls;
        }

        @Override public String getId() { return id; }
        @Override public int getVersion() { return 1; }
        @Override public boolean supports(FileObject fileObject) { return true; }

        @Override
        public Map<String, String> map(FileObject file, Object inputData) {
            calls.incrementAndGet();
            Map<String, String> map = new HashMap<>();
            try {
                String text = file.getText();
                String[] tokens = text.split("\\s+");
                for (int i = 0; i < tokens.length - 1; i++) {
                    if ("class".equals(tokens[i])) {
                        map.put(tokens[i + 1], file.getPath());
                    }
                }
            } catch (IOException ignored) {
            }
            return map;
        }

        @Override
        public boolean isValueForFile(String value, int fileId) {
            String pathForId = indexManager.getFilePath(fileId);
            return value.equals(pathForId);
        }

        @Override public byte[] serializeKey(String key) { return key.getBytes(StandardCharsets.UTF_8); }
        @Override public byte[] serializeValue(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public String deserializeKey(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
        @Override public String deserializeValue(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
    }

    private static final class ExtensionIndexer implements IndexDefinition<String, String> {
        private final String id;
        private final AtomicInteger calls;

        private ExtensionIndexer(String id, AtomicInteger calls) {
            this.id = id;
            this.calls = calls;
        }

        @Override public String getId() { return id; }
        @Override public int getVersion() { return 1; }
        @Override public boolean supports(FileObject fileObject) { return true; }

        @Override
        public Map<String, String> map(FileObject file, Object inputData) {
            calls.incrementAndGet();
            String ext;
            try {
                ext = file.getExtension();
            } catch (Throwable t) {
                ext = "";
            }
            if (ext == null) ext = "";
            ext = ext.toLowerCase(Locale.ROOT);

            Map<String, String> map = new HashMap<>();
            map.put(ext, file.getPath());
            return map;
        }

        @Override public boolean isValueForFile(String value, int fileId) { return true; }

        @Override public byte[] serializeKey(String key) { return key.getBytes(StandardCharsets.UTF_8); }
        @Override public byte[] serializeValue(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public String deserializeKey(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
        @Override public String deserializeValue(byte[] data) { return new String(data, StandardCharsets.UTF_8); }
    }
}
