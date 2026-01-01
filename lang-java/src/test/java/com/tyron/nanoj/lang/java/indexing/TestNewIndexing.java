package com.tyron.nanoj.lang.java.indexing;

import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.core.indexing.IndexManagerImpl;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

public class TestNewIndexing extends BaseIdeTest {

    private static <T> T logTime(String tag, Callable<T> callable) throws Exception {
        var start = Instant.now();
        try {
            return callable.call();
        } finally {
            var between = Duration.between(start, Instant.now());
            System.out.println(tag + " took " + between.toMillis());
        }
    }

    @Test
    public void test() throws Exception {
        IndexManagerImpl indexManager = (IndexManagerImpl) IndexManager.getInstance();
        indexManager.register(new JavaFullClassNameIndex(project));
        indexManager.register(new JavaBinaryStubIndexer(project));
        indexManager.register(new JavaPackageIndex(project));
        indexManager.register(new ShortClassNameIndex(project));

        List<FileObject> roots = List.of(VirtualFileManager.getInstance().find(URI.create("jrt:/modules")));
        logTime("Indexing", () -> {
            indexManager.processRoots(roots);
            return null;
        });
    }
}


class ProgressTracker {
    private final AtomicLong current = new AtomicLong(0);
    private final String taskName;

    public ProgressTracker(String taskName) {
        this.taskName = taskName;
    }

    public void report(int count) {
        long completed = current.addAndGet(count);
        System.out.println("[" + taskName + "] Completed: " + completed);
    }
}

