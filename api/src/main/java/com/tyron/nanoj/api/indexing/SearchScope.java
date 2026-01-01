package com.tyron.nanoj.api.indexing;

@FunctionalInterface
public interface SearchScope {
    /**
     * @param fileId The internal ID of the file being checked.
     * @return true if this file should be included in search results.
     */
    boolean contains(int fileId);

    // --- Common Scopes ---

    static SearchScope all() {
        return fileId -> true;
    }

    static SearchScope of(SearchScope... scopes) {
        return (fileId) -> {
            for (SearchScope scope : scopes) {
                if (scope.contains(fileId)) return true;
            }

            return false;
        };
    }
}