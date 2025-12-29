package com.tyron.nanoj.core.dumb;

import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class DumbModeIndexingTest extends BaseIdeTest {

    @Test
    public void quickIndexingDoesNotEnterDumbMode() {
        project.getConfiguration().setProperty(IndexManager.DUMB_THRESHOLD_MS_KEY, "10000");

        IndexManager indexManager = IndexManager.getInstance(project);
        indexManager.register(new QuickIndexDefinition());

        DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);
        assertFalse(dumbService.isDumb());

        file("src/main/java/A.java", "class A {}".getBytes(StandardCharsets.UTF_8));
        indexManager.flush();

        assertFalse(dumbService.isDumb(), "Expected to stay smart for fast per-file indexing");
    }

    @Test
    public void longIndexingEntersDumbModeAfterThreshold() throws Exception {
        project.getConfiguration().setProperty(IndexManager.DUMB_THRESHOLD_MS_KEY, "50");

        IndexManager indexManager = IndexManager.getInstance(project);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        indexManager.register(new BlockingIndexDefinition(started, release));

        file("src/main/java/A.java", "class A {}".getBytes(StandardCharsets.UTF_8));

        assertTrue(started.await(2, TimeUnit.SECONDS), "Indexing did not start");

        DumbService dumbService = ProjectServiceManager.getService(project, DumbService.class);

        long deadline = System.currentTimeMillis() + 2000;
        while (!dumbService.isDumb() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(dumbService.isDumb(), "Expected dumb mode for slow indexing");

        release.countDown();
        indexManager.flush();

        assertFalse(dumbService.isDumb(), "Expected smart mode after indexing completes");
    }

    private static final class QuickIndexDefinition implements IndexDefinition<String, String> {

        @Override
        public String getId() {
            return "quick_test_index";
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
        public Map<String, String> map(FileObject file, Object helper) {
            return Collections.singletonMap("k", "v");
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

    private static final class BlockingIndexDefinition implements IndexDefinition<String, String> {
        private final CountDownLatch started;
        private final CountDownLatch release;

        private BlockingIndexDefinition(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override
        public String getId() {
            return "blocking_test_index";
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
        public Map<String, String> map(FileObject file, Object helper) {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Collections.singletonMap("k", "v");
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
