package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

public class IndexManagerConcurrencyTest extends BaseIdeTest {

    @Test
    void concurrentUpdateAndReadDoesNotCorruptDb() throws Exception {
        IndexManager indexManager = IndexManager.getInstance(project);
        ProjectServiceManager.registerInstance(project, IndexManager.class, indexManager);

        String indexId = "throwing_index";
        indexManager.register(new ThrowingIndex(indexId));

        var f = file("src/main/java/p/Foo.java", "package p; class Foo {}\n");

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                start.await();
                // Make the indexer throw sometimes to simulate parse/index failures.
                for (int i = 0; i < 50; i++) {
                    f.setContent((i % 2 == 0)
                            ? "package p; class Foo { void m(){ } }\n"
                            : "package p; class Foo { void m(){ BROKEN } }\n");
                    indexManager.updateFile(f);
                }

                // Ensure all queued writes have finished so failures surface before the test ends.
                indexManager.flush();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        }, "idx-writer-test");

        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    indexManager.processValues(indexId, "k", SearchScope.all(), (fileId, value) -> true);
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        }, "idx-reader-test");

        writer.start();
        reader.start();
        start.countDown();

        done.await(10, TimeUnit.SECONDS);

        // If MapDB was corrupted (GetVoid) or locks were broken, we'd see a failure here.
        assertNull(failure.get(), () -> "Unexpected failure: " + failure.get());
    }

    private static final class ThrowingIndex implements IndexDefinition<String, String> {
        private final String id;
        private int counter;

        private ThrowingIndex(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public boolean supports(com.tyron.nanoj.api.vfs.FileObject file) {
            return true;
        }

        @Override
        public Map<String, String> map(com.tyron.nanoj.api.vfs.FileObject file, Object helper) {
            counter++;
            if (counter % 3 == 0) {
                throw new IllegalArgumentException("synthetic failure");
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
