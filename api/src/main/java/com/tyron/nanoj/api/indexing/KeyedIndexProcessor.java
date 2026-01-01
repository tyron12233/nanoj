package com.tyron.nanoj.api.indexing;

/**
 * Like {@link IndexProcessor} but also exposes the index key.
 */
public interface KeyedIndexProcessor<V> {

    /**
     * @param key   the index key (as stored in MapDB)
     * @param fileId the fileId that produced this value
     * @param value  the deserialized value
     * @return true to continue, false to stop searching
     */
    boolean process(String key, int fileId, V value);
}
