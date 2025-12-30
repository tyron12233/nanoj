package com.tyron.nanoj.core.indexing.iterators;

import com.tyron.nanoj.api.vfs.FileObject;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.HashSet;

/**
 * Fast dedup filter for roots/files using VFS file ids.
 */
public final class IndexableFilesDeduplicateFilter {

    private final IntOpenHashSet seenIds = new IntOpenHashSet();
    private final HashSet<String> seenPaths = new HashSet<>();

    public boolean shouldAccept(FileObject file) {
        if (file == null) {
            return false;
        }

        int id;
        try {
            id = file.getId();
        } catch (Throwable t) {
            id = 0;
        }

        if (id > 0) {
            return seenIds.add(id);
        }

        String path;
        try {
            path = file.getPath();
        } catch (Throwable t) {
            path = null;
        }
        if (path == null) {
            return false;
        }
        return seenPaths.add(path);
    }
}
