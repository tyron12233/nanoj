package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.indexing.iterators.IndexingIteratorsProvider;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.core.indexing.iterators.IndexableFilesDeduplicateFilter;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Lazy iterator/collector for indexing inputs.
 * <p>
 * This is intentionally an {@link Iterable} to avoid building large intermediate lists.
 *
 * <p><b>Policy:</b>
 * <ul>
 *   <li>Only Project source roots are contributed.</li>
 *   <li>The Project build directory is always excluded.</li>
 * </ul>
 */
public final class IndexingInputCollector implements Iterable<FileObject> {

    private final Project project;

    public IndexingInputCollector(Project project) {
        this.project = project;
    }

    @Override
    public Iterator<FileObject> iterator() {
        if (project == null || !project.isOpen()) {
            return List.<FileObject>of().iterator();
        }

        final String buildDirPath = safePath(safeBuildDir(project));
        final List<String> sourceRootPaths = safeSourceRootPaths(project);


        final IndexableFilesDeduplicateFilter dedup = new IndexableFilesDeduplicateFilter();
        final List<com.tyron.nanoj.api.indexing.iterators.IndexableFilesIterator> iterators = safeIterators(project);
        final List<FileObject> fallbackRoots;
        if (iterators.isEmpty()) {
            List<FileObject> roots;
            try {
                roots = project.getSourceRoots();
            } catch (Throwable ignored) {
                roots = List.of();
            }
            fallbackRoots = roots != null ? roots : List.of();
        } else {
            fallbackRoots = List.of();
        }

        return new Iterator<>() {
            int knownIndex = 0;
            int fallbackIndex = 0;
            int iteratorIndex = 0;

            // state for iterators-based traversal
            final ArrayList<FileObject> bufferedRoots = new ArrayList<>();
            int bufferedIndex = 0;

            FileObject next;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                next = computeNext();
                return next != null;
            }

            @Override
            public FileObject next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                FileObject out = next;
                next = null;
                return out;
            }

            private FileObject computeNext() {
                while (true) {
                    while (bufferedIndex < bufferedRoots.size()) {
                        FileObject root = bufferedRoots.get(bufferedIndex++);
                        if (root == null) continue;
                        if (isInOrUnder(safePath(root), buildDirPath)) continue;
                        if (!dedup.shouldAccept(root)) continue;
                        return root;
                    }

                    bufferedRoots.clear();
                    bufferedIndex = 0;

                    if (!iterators.isEmpty()) {
                        if (iteratorIndex >= iterators.size()) {
                            break;
                        }
                        var it = iterators.get(iteratorIndex++);
                        if (it == null) {
                            continue;
                        }
                        try {
                            it.iterateRoots(root -> {
                                if (root != null) {
                                    bufferedRoots.add(root);
                                }
                            });
                        } catch (Throwable ignored) {
                        }
                        continue;
                    }
                    break;
                }

                // 3) fallback source roots
                while (fallbackIndex < fallbackRoots.size()) {
                    FileObject root = fallbackRoots.get(fallbackIndex++);
                    if (root == null) continue;
                    if (isInOrUnder(safePath(root), buildDirPath)) continue;
                    if (!dedup.shouldAccept(root)) continue;
                    return root;
                }

                return null;
            }
        };
    }

    private static List<com.tyron.nanoj.api.indexing.iterators.IndexableFilesIterator> safeIterators(Project project) {
        if (project == null) return List.of();
        try {
            IndexingIteratorsProvider provider = project.getService(IndexingIteratorsProvider.class);
            if (provider == null) return List.of();
            List<com.tyron.nanoj.api.indexing.iterators.IndexableFilesIterator> it = provider.getIterators();
            return it != null ? it : List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static FileObject safeBuildDir(Project project) {
        if (project == null) return null;
        try {
            return project.getBuildDirectory();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> safeSourceRootPaths(Project project) {
        if (project == null) return List.of();
        try {
            List<FileObject> roots = project.getSourceRoots();
            if (roots == null || roots.isEmpty()) return List.of();
            ArrayList<String> out = new ArrayList<>(roots.size());
            for (FileObject r : roots) {
                String p = safePath(r);
                if (p != null) out.add(p);
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static String safePath(FileObject file) {
        if (file == null) return null;
        try {
            return file.getPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUnderAnyRoot(String path, List<String> rootPaths) {
        if (path == null || rootPaths == null || rootPaths.isEmpty()) return false;
        for (String rp : rootPaths) {
            if (isInOrUnder(path, rp)) return true;
        }
        return false;
    }

    private static boolean isInOrUnder(String path, String dirPath) {
        if (path == null || dirPath == null || dirPath.isBlank()) return false;
        if (path.equals(dirPath)) return true;
        if (!path.startsWith(dirPath)) return false;
        int n = dirPath.length();
        return path.length() > n && path.charAt(n) == '/';
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
