package com.tyron.nanoj.api.indexing;

import com.tyron.nanoj.api.application.ApplicationManager;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.vfs.FileObject;

import java.util.List;
import java.util.Map;

public interface IndexManager extends Disposable {

    static IndexManager getInstance() {
        return ApplicationManager.getApplication().getService(IndexManager.class);
    }

    void register(IndexDefinition<?, ?> indexer);

    void processRoots(Iterable<FileObject> roots);

    void addProgressListener(IndexingProgressListener listener);

    void removeProgressListener(IndexingProgressListener listener);

    IndexingProgressSnapshot getProgressSnapshot();

    <K, V> boolean processValues(String indexId, String key, SearchScope scope, IndexProcessor<V> processor);

    <K, V> boolean processPrefix(String indexId, String prefix, SearchScope scope, IndexProcessor<V> processor);

    <K, V> boolean processPrefixWithKeys(String indexId, String prefix, SearchScope scope, KeyedIndexProcessor<V> processor);

    <K, V> Map<String, List<V>> searchPrefix(String indexId, String prefix);

    <K, V> List<V> search(String indexId, String key);
}
