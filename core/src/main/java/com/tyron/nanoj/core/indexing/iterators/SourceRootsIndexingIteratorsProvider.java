package com.tyron.nanoj.core.indexing.iterators;

import com.tyron.nanoj.api.indexing.iterators.IndexableFilesIterator;
import com.tyron.nanoj.api.indexing.iterators.IndexingIteratorsProvider;
import com.tyron.nanoj.api.project.Project;

import java.util.List;

public final class SourceRootsIndexingIteratorsProvider implements IndexingIteratorsProvider {

    private final Project project;

    public SourceRootsIndexingIteratorsProvider(Project project) {
        this.project = project;
    }

    @Override
    public List<IndexableFilesIterator> getIterators() {
        return List.of(new ProjectSourceRootsIterator(project), new JdkRootsIterator(project));
    }
}
