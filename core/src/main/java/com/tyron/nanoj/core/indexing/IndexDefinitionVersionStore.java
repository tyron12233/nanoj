package com.tyron.nanoj.core.indexing;

import org.mapdb.DB;
import org.mapdb.Serializer;

import java.util.Map;

/**
 * Persistent storage of registered {@code IndexDefinition} versions.
 * <p>
 * Used to detect index schema/code changes across process restarts.
 */
final class IndexDefinitionVersionStore {

    private final Map<String, Integer> indexIdToVersion;

    IndexDefinitionVersionStore(DB db) {
        this.indexIdToVersion = db.hashMap("sys_index_definition_versions", Serializer.STRING, Serializer.INTEGER)
                .createOrOpen();
    }

    Integer getStoredVersion(String indexId) {
        if (indexId == null || indexId.isBlank()) {
            return null;
        }
        return indexIdToVersion.get(indexId);
    }

    /**
     * Records the current version, returning the previously stored version (or null if none).
     */
    Integer putVersion(String indexId, int version) {
        if (indexId == null || indexId.isBlank()) {
            return null;
        }
        return indexIdToVersion.put(indexId, version);
    }
}
