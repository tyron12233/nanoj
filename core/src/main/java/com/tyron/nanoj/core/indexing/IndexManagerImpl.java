package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.api.indexing.IndexProcessor;
import com.tyron.nanoj.api.indexing.SearchScope;
import com.tyron.nanoj.api.project.Project;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class IndexManagerImpl extends AbstractIndexManager {

    private final ReentrantReadWriteLock indexLock = new ReentrantReadWriteLock();

    public IndexManagerImpl() {

    }

    /**
     * Iterates over values for a specific exact key.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processValues(String indexId, String key, SearchScope scope, IndexProcessor<V> processor) {
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return true;

            Map<MapDBIndexWrapper.InvertedKey, byte[]> rawPackets = wrapper.getValues(key);

            for (Map.Entry<MapDBIndexWrapper.InvertedKey, byte[]> entry : rawPackets.entrySet()) {
                MapDBIndexWrapper.InvertedKey invertedKey = entry.getKey();
                byte[] v = entry.getValue();
                if (scope.contains(invertedKey.fileId())) {
                    V value = def.deserializeValue(v);

                    if (!processor.process(invertedKey.fileId(), value)) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Iterates over values where the key starts with the given prefix.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processPrefix(String indexId, String prefix, SearchScope scope, IndexProcessor<V> processor) {
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return true;

            Map<MapDBIndexWrapper.InvertedKey, byte[]> prefixMatches = wrapper.searchPrefix(prefix);

            for (Map.Entry<MapDBIndexWrapper.InvertedKey, byte[]> entry : prefixMatches.entrySet()) {
                if (scope.contains(entry.getKey().fileId())) {
                    V value = def.deserializeValue(entry.getValue());
                    
                    if (!processor.process(entry.getKey().fileId(), value)) {
                        return false;
                    }
                }
            }

            return true;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Iterates over values where the key starts with the given prefix, also exposing the matched key.
     */
    @SuppressWarnings("unchecked")
    public <K, V> boolean processPrefixWithKeys(String indexId, String prefix, SearchScope scope, com.tyron.nanoj.api.indexing.KeyedIndexProcessor<V> processor) {
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return true;

            Map<MapDBIndexWrapper.InvertedKey, byte[]> prefixMatches = wrapper.searchPrefix(prefix);

            for (Map.Entry<MapDBIndexWrapper.InvertedKey, byte[]> entry : prefixMatches.entrySet()) {
                String key = entry.getKey().key();
                byte[] value = entry.getValue();

                if (scope.contains(entry.getKey().fileId())) {
                    V v = def.deserializeValue(value);

                    if (!processor.process(key, entry.getKey().fileId(), v)) {
                        return false;
                    }
                }
            }

            return true;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    public <K, V> List<V> search(String indexId, String key) {
        List<V> values = new ArrayList<>();
        processValues(indexId, key, SearchScope.all(), (IndexProcessor<V>) (fileId, value) -> {
                values.add(value);
                return true;
        });
        return values;
    }

    /**
     * Efficient Prefix Search via B-Tree.
     */
    @SuppressWarnings("unchecked")
    public <K, V> Map<String, List<V>> searchPrefix(String indexId, String prefix) {
        indexLock.readLock().lock();
        try {
            MapDBIndexWrapper wrapper = wrappers.get(indexId);
            IndexDefinition<K, V> def = (IndexDefinition<K, V>) definitions.get(indexId);

            if (wrapper == null || def == null) return Collections.emptyMap();

            Map<MapDBIndexWrapper.InvertedKey, byte[]> rawMap = wrapper.searchPrefix(prefix);
            Map<String, List<V>> result = new HashMap<>();

            for (Map.Entry<MapDBIndexWrapper.InvertedKey, byte[]> entry : rawMap.entrySet()) {
                Objects.requireNonNull(result.putIfAbsent(entry.getKey().key(), new ArrayList<>()))
                        .add(def.deserializeValue(entry.getValue()));
            }
            return result;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @Override
    public void dispose() {

    }
}
