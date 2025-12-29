package com.tyron.nanoj.lang.java.indexing;

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.tyron.nanoj.api.vfs.FileObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared per-thread cache for parsed {@link ClassFile} instances.
 * <p>
 * Indexing typically runs multiple Java binary indexers over the same {@code .class} file.
 * Without sharing, each indexer re-opens an InputStream and reparses the class file.
 * <p>
 * This helper allows indexers to reuse a single parsed {@link ClassFile} per file (per thread).
 * It is intentionally:
 * - Thread-local (no locking; indexing runs on a single writer thread)
 * - Small LRU (bounds memory usage)
 * - Content-hash aware (safe if a class file changes)
 */
final class SharedClassFile {

    private static final int MAX_ENTRIES = 512;

    private static final ThreadLocal<LinkedHashMap<String, Entry>> CACHE = ThreadLocal.withInitial(() ->
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }
    );

    private record Entry(int contentHash, ClassFile classFile) {
    }

    private SharedClassFile() {
    }

    static ClassFile get(FileObject file, Object helper) throws IOException, ConstantPoolException {
        Objects.requireNonNull(file, "file");

        byte[] bytes;
        if (helper instanceof byte[]) {
            bytes = (byte[]) helper;
        } else {
            // fallback for callers that didn't provide helper bytes.
            bytes = file.getContent();
        }

        String key = file.getPath();
        int hash = Arrays.hashCode(bytes);

        LinkedHashMap<String, Entry> map = CACHE.get();
        Entry cached = map.get(key);
        if (cached != null && cached.contentHash == hash) {
            return cached.classFile;
        }

        try (InputStream in = new BufferedInputStream(new ByteArrayInputStream(bytes))) {
            ClassFile cf = ClassFile.read(in);
            map.put(key, new Entry(hash, cf));
            return cf;
        }
    }
}
