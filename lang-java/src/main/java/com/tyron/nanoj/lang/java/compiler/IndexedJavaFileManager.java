package com.tyron.nanoj.lang.java.compiler;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.vfs.VirtualFileSystem;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.indexing.SearchScope;
import com.tyron.nanoj.core.indexing.Scopes;
import com.tyron.nanoj.lang.java.indexing.JavaFullClassNameIndex;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/**
 * A Javac FileManager that bypasses standard classpath scanning.
 * Instead, it queries the {@link IndexManager} to locate classes instantly.
 */
public class IndexedJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final IndexManager indexManager;
    private final SearchScope sourceScope;
    private final SearchScope libScope;

    public IndexedJavaFileManager(JavaFileManager delegate, Project project) {
        super((StandardJavaFileManager) delegate);
        this.indexManager = IndexManager.getInstance(project);
        this.sourceScope = Scopes.projectSource(project);
        this.libScope = Scopes.libraries(project);
    }

    private static FileObject findFileObject(String pathOrUri) {
        if (pathOrUri == null || pathOrUri.isBlank()) {
            return null;
        }

        // VirtualFileSystem.find(String) only detects "://" URIs; NanoJ also uses schemes like "jrt:/".
        if (pathOrUri.startsWith("jrt:") || pathOrUri.startsWith("jar:") || pathOrUri.startsWith("file:")) {
            return VirtualFileSystem.getInstance().find(URI.create(pathOrUri));
        }

        return VirtualFileSystem.getInstance().find(new File(pathOrUri));
    }

    private static boolean isPathCompatibleWithKind(String pathOrUri, JavaFileObject.Kind kind, String requestedClassName) {
        if (pathOrUri == null || kind == null) {
            return false;
        }

        String simpleName = requestedClassName;
        int lastDot = requestedClassName == null ? -1 : requestedClassName.lastIndexOf('.');
        if (lastDot != -1) {
            simpleName = requestedClassName.substring(lastDot + 1);
        }

        return switch (kind) {
            case SOURCE -> pathOrUri.endsWith("/" + simpleName + ".java") || pathOrUri.endsWith(simpleName + ".java");
            case CLASS -> pathOrUri.endsWith("/" + simpleName + ".class") || pathOrUri.endsWith(simpleName + ".class");
            default -> false;
        };
    }


    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        // Delegate Platform/System modules to standard manager (rt.jar / android.jar complexity)
        if (location == StandardLocation.PLATFORM_CLASS_PATH && packageName.startsWith("java.")) {
            return super.list(location, packageName, kinds, recurse);
        }

        List<JavaFileObject> results = new ArrayList<>();
        SearchScope scope = determineScope(location);

        if (scope == null) {
            return super.list(location, packageName, kinds, recurse);
        }

        // QUERY INDEX: "Who is in package X?"
        indexManager.processValues(
                JavaPackageIndex.ID,
                packageName,
                scope,
                (fileId, entry) -> {
                    JavaPackageIndex.Entry idxEntry = (JavaPackageIndex.Entry) entry;

                    if (kinds.contains(idxEntry.kind)) {
                        String path = indexManager.getFilePath(fileId);
                        if (path != null) {
                            FileObject fo = findFileObject(path);
                            if (fo != null) {
                                // CONSTRUCT BINARY NAME: package + . + simpleName
                                String binaryName = packageName.isEmpty()
                                        ? idxEntry.simpleName
                                        : packageName + "." + idxEntry.simpleName;

                                results.add(new IndexedJavaFileObject(fo, idxEntry.kind, binaryName));
                            }
                        }
                    }
                    return true;
                }
        );

        return results;
    }

    /**
     * Intercepts explicit requests for a class.
     * e.g. "Give me com.example.MyClass"
     */
    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
        SearchScope scope = determineScope(location);
        if (scope == null) {
            return super.getJavaFileForInput(location, className, kind);
        }

        if (className == null || className.isBlank()) {
            return super.getJavaFileForInput(location, className, kind);
        }

        // Fast-path: resolve exact FQN -> file via index.
        // This is especially useful for CLASS_PATH where scanning classpath roots is expensive.
        final JavaFileObject[] found = new JavaFileObject[1];
        indexManager.processValues(JavaFullClassNameIndex.ID, className, scope, (fileId, pathOrUri) -> {
            if (!(pathOrUri instanceof String p)) {
                return true;
            }
            if (!isPathCompatibleWithKind(p, kind, className)) {
                return true;
            }

            FileObject fo = findFileObject(p);
            if (fo == null) {
                return true;
            }

            found[0] = new IndexedJavaFileObject(fo, kind, className);
            return false;
        });

        if (found[0] != null) {
            return found[0];
        }

        // we can try to use our Index to find the file path quickly
        // However, JavaPackageIndex is keyed by Package, not FQN.
        // we could use ShortClassNameIndex if we added FQN checking,
        // OR we can rely on list() which Javac calls heavily anyway.

        // Optimization: If we had an FQN -> FileID index, we would query it here. (TODO)
        // For now, we fall back to list() logic or super if specific lookup is needed.
        // But since we implemented list() robustly, Javac usually relies on that
        // to populate its symbol table and won't call this as often for discovery.

        return super.getJavaFileForInput(location, className, kind);
    }

    private SearchScope determineScope(Location location) {
        if (location == StandardLocation.SOURCE_PATH) return sourceScope;
        if (location == StandardLocation.CLASS_PATH) return libScope;
        return null;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof IndexedJavaFileObject) {
            return ((IndexedJavaFileObject) file).inferBinaryName();
        }
        return super.inferBinaryName(location, file);
    }
}