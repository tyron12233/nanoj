package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.vfs.VirtualFileManager;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Collects indexable inputs (project roots, libraries, boot classpath, JRT) and submits them to {@link IndexManager}.
 * <p>
 * This keeps {@link IndexManager} focused on deciding what to index, while this class decides what files/roots to offer.
 */
public final class IndexingInputCollector {

    private final Project project;

    public IndexingInputCollector(Project project) {
        this.project = project;
    }

    /**
     * Collects inputs and submits them to the given {@link IndexManager}.
     * <p>
     * Uses {@link IndexManager#updateFilesAsync(Iterable)} so {@link IndexManager} can decide staleness per index.
     */
    public Future<?> submitAll(IndexManager indexManager) {
        if (indexManager == null) {
            return null;
        }
        List<FileObject> inputs = collectAll(indexManager);
        if (inputs.isEmpty()) {
            return null;
        }
        return indexManager.updateFilesAsync(inputs);
    }

    /**
     * Best-effort list of inputs to offer for indexing.
     */
    public List<FileObject> collectAll(IndexManager indexManager) {
        if (project == null || !project.isOpen()) {
            return List.of();
        }

        Set<FileObject> out = new LinkedHashSet<>();

        // Prefer already-known leaf files (works in tests where folder traversal isn't available).
        if (indexManager != null) {
            try {
                for (String p : indexManager.getKnownFilePathsSnapshot()) {
                    FileObject fo = resolvePathToFileObject(p);
                    if (fo != null && !fo.isFolder()) {
                        out.add(fo);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            FileObject root = project.getRootDirectory();
            if (root != null) {
                out.add(root);
            }
        } catch (Throwable ignored) {
        }

        try {
            out.addAll(project.getSourceRoots());
        } catch (Throwable ignored) {
        }

        try {
            out.addAll(project.getResourceRoots());
        } catch (Throwable ignored) {
        }

        try {
            out.addAll(project.getClassPath());
        } catch (Throwable ignored) {
        }

        try {
            out.addAll(project.getBootClassPath());
        } catch (Throwable ignored) {
        }

        // Optional: JRT modules root (desktop JDKs). IndexManager understands jrt:/ folder traversal.
        try {
            FileObject modulesRoot = VirtualFileManager.getInstance().find(URI.create("jrt:/modules"));
            if (modulesRoot != null && modulesRoot.exists() && modulesRoot.isFolder()) {
                out.add(modulesRoot);
            }
        } catch (Throwable ignored) {
        }

        // Remove nulls while preserving order.
        ArrayList<FileObject> result = new ArrayList<>(out.size());
        for (FileObject fo : out) {
            if (fo != null) {
                result.add(fo);
            }
        }
        return result;
    }

    private static FileObject resolvePathToFileObject(String path) {
        try {
            if (path == null || path.isBlank()) {
                return null;
            }
            if (path.startsWith("jrt:/") || path.startsWith("jar:") || path.contains("://")) {
                return VirtualFileManager.getInstance().find(URI.create(path));
            }
            return VirtualFileManager.getInstance().find(new File(path));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
