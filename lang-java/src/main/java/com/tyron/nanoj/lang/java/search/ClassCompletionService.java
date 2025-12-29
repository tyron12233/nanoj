package com.tyron.nanoj.lang.java.search;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.indexing.SearchScope;
import com.tyron.nanoj.core.indexing.Scopes;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.lang.java.indexing.ShortClassNameIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClassCompletionService implements Disposable {

    public static ClassCompletionService getInstance(Project project) {
        return ProjectServiceManager.getService(project, ClassCompletionService.class);
    }

    private final IndexManager indexManager;
    private final Project project;

    public ClassCompletionService(Project project) {
        this.project = project;
        this.indexManager = IndexManager.getInstance(project);
    }

    /**
     * Finds classes starting with the given prefix.
     * Uses the Processor pattern to avoid allocating huge lists for large queries.
     */
    public List<String> findClasses(String prefix) {
        // Default to finding everything (Source + Libraries)
        return findClasses(prefix, Scopes.all(project));
    }

    /**
     * Finds classes with a specific scope.
     */
    public List<String> findClasses(String prefix, SearchScope scope) {
        if (prefix == null || prefix.isEmpty()) return Collections.emptyList();

        List<String> results = new ArrayList<>();
        final int LIMIT = 50;

        // Use the processor to collect results
        indexManager.processPrefix(
                ShortClassNameIndex.ID,
                prefix,
                scope,
                (fileId, value) -> {
                    // 'value' here is the FQN String (from ShortClassNameIndex)
                    if (value instanceof String) {
                        results.add((String) value);
                    }

                    // Return false to STOP if we hit the limit
                    return results.size() < LIMIT;
                }
        );

        return results;
    }

    /**
     * Example: Find only classes defined in the user's source code.
     */
    public List<String> findProjectClasses(String prefix) {
        return findClasses(prefix, Scopes.projectSource(project));
    }

    @Override
    public void dispose() {
        // No resources to close
    }
}