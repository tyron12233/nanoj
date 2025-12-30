package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileChangeListener;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Read-only {@code jar:} filesystem.
 *
 * Supports URIs like:
 * - {@code jar:file:///path/to/lib.jar!/com/example/Foo.class}
 */
public final class JarFileSystem implements FileSystem {

    private static final JarFileSystem INSTANCE = new JarFileSystem();

    public static JarFileSystem getInstance() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<FileChangeListener> listeners = new CopyOnWriteArrayList<>();

    // Cache of jar indexes by underlying jar URI string
    private final Map<String, JarIndex> indexCache = new ConcurrentHashMap<>();

    private JarFileSystem() {
    }

    @Override
    public String getScheme() {
        return "jar";
    }

    @Override
    public FileObject findResource(URI uri) {
        Objects.requireNonNull(uri, "uri");

        ParsedJarUri parsed = parseJarUri(uri);
        if (parsed == null) {
            throw new IllegalArgumentException("Unsupported jar URI: " + uri);
        }

        String entryPath = normalizeEntryPath(parsed.entryPath);
        JarIndex index = getOrBuildIndex(parsed.jarUri);
        return new JarFileObject(parsed.jarUri, entryPath, index, this);
    }

    @Override
    public FileObject findResource(String path) {
        Objects.requireNonNull(path, "path");
        if (path.startsWith("jar:")) {
            return findResource(URI.create(path));
        }
        // Accept "<jarPath>!/entry" as a convenience.
        int bang = path.indexOf("!/");
        if (bang > 0) {
            URI jarUri = new File(path.substring(0, bang)).toURI();
            String entry = path.substring(bang + 2);
            return findResource(URI.create("jar:" + jarUri + "!/" + entry));
        }
        throw new IllegalArgumentException("Unsupported jar path: " + path);
    }

    @Override
    public void refresh(boolean asynchronous) {
        indexCache.clear();
    }

    @Override
    public void addFileChangeListener(FileChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeFileChangeListener(FileChangeListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    JarIndex getOrBuildIndex(URI jarUri) {
        String key = jarUri.toString();
        return indexCache.computeIfAbsent(key, k -> buildIndex(jarUri));
    }

    private JarIndex buildIndex(URI jarUri) {
        FileObject jarFo = VirtualFileSystem.getInstance().find(jarUri);

        // Fast path: real file on disk.
        if ("file".equalsIgnoreCase(jarUri.getScheme())) {
            File f = new File(jarUri);
            if (f.exists() && f.isFile()) {
                return buildIndexFromZipFile(jarUri, f);
            }
        }

        // Fallback: read whole jar into memory and parse.
        try {
            byte[] bytes = jarFo.getContent();
            return buildIndexFromBytes(jarUri, bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read jar content: " + jarUri, e);
        }
    }

    private JarIndex buildIndexFromZipFile(URI jarUri, File jarFile) {
        Set<String> allDirs = new HashSet<>();
        Set<String> allFiles = new HashSet<>();
        Map<String, List<String>> childrenByDir = new HashMap<>();

        allDirs.add("");
        try (ZipFile zip = new ZipFile(jarFile)) {
            zip.stream().forEach(entry -> {
                String name = normalizeEntryPath(entry.getName());
                if (entry.isDirectory()) {
                    addDir(allDirs, childrenByDir, name);
                } else {
                    addFile(allDirs, allFiles, childrenByDir, name);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to open jar: " + jarFile, e);
        }

        normalizeChildren(childrenByDir);
        return JarIndex.forZipFile(jarUri, jarFile, allDirs, allFiles, childrenByDir);
    }

    private JarIndex buildIndexFromBytes(URI jarUri, byte[] jarBytes) {
        Set<String> allDirs = new HashSet<>();
        Set<String> allFiles = new HashSet<>();
        Map<String, List<String>> childrenByDir = new HashMap<>();
        Map<String, byte[]> fileBytes = new HashMap<>();

        allDirs.add("");
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = normalizeEntryPath(e.getName());
                if (e.isDirectory()) {
                    addDir(allDirs, childrenByDir, name);
                } else {
                    addFile(allDirs, allFiles, childrenByDir, name);
                    fileBytes.put(name, readAll(zin));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse jar bytes: " + jarUri, e);
        }

        normalizeChildren(childrenByDir);
        return JarIndex.forInMemory(jarUri, jarBytes, allDirs, allFiles, childrenByDir, fileBytes);
    }

    private static void normalizeChildren(Map<String, List<String>> childrenByDir) {
        for (Map.Entry<String, List<String>> e : childrenByDir.entrySet()) {
            e.getValue().sort(Comparator.naturalOrder());
        }
    }

    private static void addDir(Set<String> allDirs, Map<String, List<String>> childrenByDir, String dirPath) {
        dirPath = normalizeDirPath(dirPath);
        if (dirPath.isEmpty()) {
            allDirs.add("");
            return;
        }
        ensureParents(allDirs, childrenByDir, dirPath);
        allDirs.add(dirPath);
        String parent = parentDir(dirPath);
        addChild(childrenByDir, parent, nameOf(dirPath));
    }

    private static void addFile(Set<String> allDirs, Set<String> allFiles, Map<String, List<String>> childrenByDir, String filePath) {
        filePath = normalizeEntryPath(filePath);
        if (filePath.isEmpty() || filePath.endsWith("/")) {
            return;
        }
        ensureParents(allDirs, childrenByDir, filePath);
        allFiles.add(filePath);
        String parent = parentDir(filePath);
        addChild(childrenByDir, parent, nameOf(filePath));
    }

    private static void ensureParents(Set<String> allDirs, Map<String, List<String>> childrenByDir, String path) {
        String parent = parentDir(path);
        while (parent != null && !parent.isEmpty()) {
            String dir = normalizeDirPath(parent);
            if (allDirs.add(dir)) {
                String pp = parentDir(dir);
                addChild(childrenByDir, pp == null ? "" : pp, nameOf(dir));
            }
            parent = parentDir(dir);
        }
        allDirs.add("");
    }

    private static void addChild(Map<String, List<String>> childrenByDir, String parentDir, String childName) {
        if (parentDir == null) parentDir = "";
        parentDir = normalizeDirPath(parentDir);
        List<String> children = childrenByDir.computeIfAbsent(parentDir, k -> new ArrayList<>());
        if (!children.contains(childName)) {
            children.add(childName);
        }
    }

    static String normalizeEntryPath(String entryPath) {
        if (entryPath == null) return "";
        entryPath = entryPath.replace('\\', '/');
        while (entryPath.startsWith("/")) entryPath = entryPath.substring(1);
        return entryPath;
    }

    static String normalizeDirPath(String dirPath) {
        dirPath = normalizeEntryPath(dirPath);
        if (!dirPath.isEmpty() && !dirPath.endsWith("/")) {
            dirPath = dirPath + "/";
        }
        return dirPath;
    }

    static String parentDir(String entryPath) {
        entryPath = normalizeEntryPath(entryPath);
        if (entryPath.isEmpty()) return null;

        // Handle directory paths like "p/"; otherwise lastIndexOf('/') points at the trailing slash
        // and parentDir("p/") would incorrectly return "p/" (infinite loop in ensureParents()).
        while (entryPath.endsWith("/")) {
            entryPath = entryPath.substring(0, entryPath.length() - 1);
        }
        if (entryPath.isEmpty()) return null;

        int idx = entryPath.lastIndexOf('/');
        if (idx < 0) return "";
        return entryPath.substring(0, idx + 1);
    }

    static String nameOf(String entryPath) {
        entryPath = normalizeEntryPath(entryPath);
        if (entryPath.endsWith("/")) {
            entryPath = entryPath.substring(0, entryPath.length() - 1);
        }
        int idx = entryPath.lastIndexOf('/');
        return idx < 0 ? entryPath : entryPath.substring(idx + 1);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static final class JarIndex {
        final URI jarUri;
        final File zipFileOnDisk;
        final byte[] jarBytes;
        final Map<String, byte[]> inMemoryEntryBytes;

        final Set<String> dirs;
        final Set<String> files;
        final Map<String, List<String>> childrenByDir;

        private JarIndex(
                URI jarUri,
                File zipFileOnDisk,
                byte[] jarBytes,
                Map<String, byte[]> inMemoryEntryBytes,
                Set<String> dirs,
                Set<String> files,
                Map<String, List<String>> childrenByDir
        ) {
            this.jarUri = jarUri;
            this.zipFileOnDisk = zipFileOnDisk;
            this.jarBytes = jarBytes;
            this.inMemoryEntryBytes = inMemoryEntryBytes;
            this.dirs = dirs;
            this.files = files;
            this.childrenByDir = childrenByDir;
        }

        static JarIndex forZipFile(URI jarUri, File zipFile, Set<String> dirs, Set<String> files, Map<String, List<String>> childrenByDir) {
            return new JarIndex(jarUri, zipFile, null, null, dirs, files, childrenByDir);
        }

        static JarIndex forInMemory(
                URI jarUri,
                byte[] jarBytes,
                Set<String> dirs,
                Set<String> files,
                Map<String, List<String>> childrenByDir,
                Map<String, byte[]> entryBytes
        ) {
            return new JarIndex(jarUri, null, jarBytes, entryBytes, dirs, files, childrenByDir);
        }

        boolean isDirectory(String entryPath) {
            return dirs.contains(normalizeDirPath(entryPath));
        }

        boolean isFile(String entryPath) {
            return files.contains(normalizeEntryPath(entryPath));
        }

        List<String> listChildren(String dirPath) {
            dirPath = normalizeDirPath(dirPath);
            List<String> kids = childrenByDir.get(dirPath);
            if (kids == null) return Collections.emptyList();
            return kids;
        }

        InputStream openEntryStream(String entryPath) throws IOException {
            entryPath = normalizeEntryPath(entryPath);
            if (zipFileOnDisk != null) {
                ZipFile zf = new ZipFile(zipFileOnDisk);
                ZipEntry ze = zf.getEntry(entryPath);
                if (ze == null) {
                    zf.close();
                    throw new IOException("Entry not found: " + entryPath);
                }
                // Ensure the ZipFile is closed when the entry stream is closed.
                InputStream raw = zf.getInputStream(ze);
                return new InputStream() {
                    @Override public int read() throws IOException { return raw.read(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return raw.read(b, off, len); }
                    @Override public void close() throws IOException {
                        try {
                            raw.close();
                        } finally {
                            zf.close();
                        }
                    }
                };
            }

            if (inMemoryEntryBytes != null) {
                byte[] data = inMemoryEntryBytes.get(entryPath);
                if (data == null) {
                    throw new IOException("Entry not found: " + entryPath);
                }
                return new ByteArrayInputStream(data);
            }

            throw new IOException("Jar index is missing data for: " + jarUri);
        }
    }

    private static final class ParsedJarUri {
        final URI jarUri;
        final String entryPath;

        private ParsedJarUri(URI jarUri, String entryPath) {
            this.jarUri = jarUri;
            this.entryPath = entryPath;
        }
    }

    private static ParsedJarUri parseJarUri(URI uri) {
        if (!"jar".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }

        // For jar URIs, URI.getSchemeSpecificPart() typically contains: "file:/x/y.jar!/p/A.class"
        String ssp = uri.getSchemeSpecificPart();
        if (ssp == null || ssp.isBlank()) {
            return null;
        }

        int bang = ssp.indexOf("!/");
        if (bang < 0) {
            return null;
        }

        String jarPart = ssp.substring(0, bang);
        String entryPart = ssp.substring(bang + 2);

        URI jarUri = URI.create(jarPart);
        return new ParsedJarUri(jarUri, entryPart);
    }
}
