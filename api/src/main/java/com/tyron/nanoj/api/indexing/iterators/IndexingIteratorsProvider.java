package com.tyron.nanoj.api.indexing.iterators;

import com.tyron.nanoj.api.project.Project;

import java.util.List;

/**
 * Provides {@link IndexableFilesIterator} instances for a {@link Project}.
 */
public interface IndexingIteratorsProvider {

    static IndexingIteratorsProvider getInstance(Project project) {
        return project.getService(IndexingIteratorsProvider.class);
    }

    /**
     * Returns the iterators to use for collecting indexable roots.
     */
    List<IndexableFilesIterator> getIterators();
}
