package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class IndexManagerDefinitionVersionStalenessTest extends BaseIdeTest {

    @Test
    public void versionBumpInvalidatesExistingIndexData() throws Exception {
        IndexManager indexManager = IndexManager.getInstance(project);

        AtomicInteger calls = new AtomicInteger();

        indexManager.register(new FixedKeyIndexer(indexManager, "ver_index", 1, "v1", calls));

        FileObject file = file("src/Hello.java", "class Hello {} ");
        indexManager.updateFile(file);
        indexManager.flush();

        Assertions.assertEquals(List.of(file.getPath()), indexManager.search("ver_index", "v1"));
        int callsAfterV1 = calls.get();
        Assertions.assertTrue(callsAfterV1 > 0, "sanity: v1 index should have run");

        // Simulate an app update: same indexId, schema version bumped.
        indexManager.register(new FixedKeyIndexer(indexManager, "ver_index", 2, "v2", calls));

        // After version bump, old key should not be visible (index was cleared).
        Assertions.assertTrue(indexManager.search("ver_index", "v1").isEmpty(), "old index data should be cleared on version bump");

        indexManager.updateFile(file);

        // Indexing inputs are submitted asynchronously; poll until v2 appears.
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadlineNs) {
            indexManager.flush();
            List<String> v2 = indexManager.search("ver_index", "v2");
            if (!v2.isEmpty()) {
                break;
            }
            Thread.sleep(25);
        }

        Assertions.assertEquals(List.of(file.getPath()), indexManager.search("ver_index", "v2"));
        Assertions.assertTrue(calls.get() > callsAfterV1, "v2 backfill should have run");
    }

    private static final class FixedKeyIndexer implements IndexDefinition<String, String> {
        private final IndexManager indexManager;
        private final String id;
        private final int version;
        private final String key;
        private final AtomicInteger calls;

        private FixedKeyIndexer(IndexManager indexManager, String id, int version, String key, AtomicInteger calls) {
            this.indexManager = indexManager;
            this.id = id;
            this.version = version;
            this.key = key;
            this.calls = calls;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public int getVersion() {
            return version;
        }

        @Override
        public boolean supports(FileObject fileObject) {
            return true;
        }

        @Override
        public Map<String, String> map(FileObject file, Object helper) {
            calls.incrementAndGet();
            Map<String, String> out = new HashMap<>();
            out.put(key, file.getPath());
            return out;
        }

        @Override
        public boolean isValueForFile(String value, int fileId) {
            String pathForId = indexManager.getFilePath(fileId);
            return value != null && value.equals(pathForId);
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
