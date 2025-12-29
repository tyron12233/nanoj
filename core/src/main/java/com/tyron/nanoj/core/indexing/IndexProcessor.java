package com.tyron.nanoj.core.indexing;

public interface IndexProcessor<V> {
    /**
     * @return true to continue, false to stop searching.
     */
    boolean process(int fileId, V value);
}