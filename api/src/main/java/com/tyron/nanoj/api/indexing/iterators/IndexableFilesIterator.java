package com.tyron.nanoj.api.indexing.iterators;

import com.tyron.nanoj.api.vfs.FileObject;

import java.util.function.Consumer;

/**
 * IntelliJ-like iterator that contributes indexable roots without eagerly enumerating all files.
 */
public interface IndexableFilesIterator {

    /**
     * A stable debug name to help with logging and troubleshooting.
     */
    String getDebugName();

    /**
     * Calls {@code consumer} for each indexable root.
     */
    void iterateRoots(Consumer<FileObject> consumer);
}
