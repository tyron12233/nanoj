package com.tyron.nanoj.core.indexing.spi;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.IndexingStampStore;

import java.util.Objects;

/**
 * Convenience base class for implementing {@link IndexDefinition}.
 * <p>
 * Provides:
 * <ul>
 *   <li>final {@link #getId()} and {@link #getVersion()} fields</li>
 *   <li>default {@link #isOutdated(FileObject, IndexingStampStore)} implementation</li>
 * </ul>
 * <p>
 * Index authors can override {@link #isOutdated(FileObject, IndexingStampStore)} to implement stronger
 * invalidation (hashes, semantic versions, external dependency stamps, etc.).
 */
public abstract class AbstractIndex<K, V> implements IndexDefinition<K, V> {

    private final String id;
    private final int version;

    protected AbstractIndex(String id, int version) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = version;
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final int getVersion() {
        return version;
    }

    @Override
    public boolean isOutdated(FileObject fileObject, IndexingStampStore stamps) {
        return IndexDefinition.super.isOutdated(fileObject, stamps);
    }
}
