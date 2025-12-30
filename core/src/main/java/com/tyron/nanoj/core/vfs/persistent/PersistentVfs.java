package com.tyron.nanoj.core.vfs.persistent;

import com.tyron.nanoj.api.vfs.FileObject;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * IntelliJ-inspired persistent VFS core:
 * - stable file ids (best-effort via fileKey for local FS)
 * - persistent snapshot records
 */
public final class PersistentVfs {

    private final PersistentVfsStorage storage;

    public PersistentVfs(Path dbFile) {
        this.storage = new PersistentVfsStorage(dbFile);
    }

    public PersistentVfsStorage storage() {
        return storage;
    }

    public synchronized void close() {
        storage.close();
    }

    public synchronized void trackRoot(URI rootUri) {
        storage.trackedRoots.put(normalizeUriString(rootUri), Boolean.TRUE);
        storage.commit();
    }

    public synchronized boolean isTrackedRoot(URI rootUri) {
        return Boolean.TRUE.equals(storage.trackedRoots.get(normalizeUriString(rootUri)));
    }

    public synchronized int getOrCreateId(URI uri, String fileKey) {
        String uriStr = normalizeUriString(uri);

        if (fileKey != null && !fileKey.isBlank()) {
            Integer existing = storage.fileKeyToId.get(fileKey);
            if (existing != null) {
                // Ensure URI maps to this id (rename / move).
                String oldUri = storage.idToUri.get(existing);
                if (oldUri != null && !oldUri.equals(uriStr)) {
                    storage.uriToId.remove(oldUri);
                }
                storage.uriToId.put(uriStr, existing);
                storage.idToUri.put(existing, uriStr);
                storage.commit();
                return existing;
            }
        }

        Integer id = storage.uriToId.get(uriStr);
        if (id != null) return id;

        int newId = storage.nextId.incrementAndGet();
        storage.uriToId.put(uriStr, newId);
        storage.idToUri.put(newId, uriStr);
        if (fileKey != null && !fileKey.isBlank()) {
            storage.fileKeyToId.put(fileKey, newId);
        }
        storage.commit();
        return newId;
    }

    public synchronized Integer getIdIfExists(URI uri) {
        return storage.uriToId.get(normalizeUriString(uri));
    }

    public synchronized URI getUriById(int id) {
        String s = storage.idToUri.get(id);
        return s != null ? URI.create(s) : null;
    }

    public synchronized void onCreated(FileObject fo, PersistentVfsRecord record) {
        URI uri = fo.toUri();
        getOrCreateId(uri, record.getFileKey());
        storage.recordsByUri.put(normalizeUriString(uri), record);
        storage.commit();
    }

    public synchronized void onDeleted(URI uri) {
        storage.recordsByUri.remove(normalizeUriString(uri));
        storage.commit();
    }

    public synchronized void onChanged(FileObject fo, PersistentVfsRecord record) {
        URI uri = fo.toUri();
        getOrCreateId(uri, record.getFileKey());
        storage.recordsByUri.put(normalizeUriString(uri), record);
        storage.commit();
    }

    public synchronized void onRenamed(URI oldUri, URI newUri, String fileKey, PersistentVfsRecord newRecord) {
        String oldStr = normalizeUriString(oldUri);
        String newStr = normalizeUriString(newUri);

        Integer id = storage.uriToId.get(oldStr);
        if (id == null) {
            id = getOrCreateId(newUri, fileKey);
        } else {
            storage.uriToId.remove(oldStr);
            storage.uriToId.put(newStr, id);
            storage.idToUri.put(id, newStr);
            if (fileKey != null && !fileKey.isBlank()) {
                storage.fileKeyToId.put(fileKey, id);
            }
        }

        PersistentVfsRecord oldRecord = storage.recordsByUri.remove(oldStr);
        storage.recordsByUri.put(newStr, newRecord != null ? newRecord : oldRecord);
        storage.commit();
    }

    public static String normalizeUriString(URI uri) {
        Objects.requireNonNull(uri, "uri");
        return uri.normalize().toString();
    }

    public static String tryGetLocalFileKey(URI uri, BasicFileAttributes attrs) {
        if (uri == null || attrs == null) return null;
        if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
        Object key = attrs.fileKey();
        return key != null ? key.toString() : null;
    }

    public static boolean isUnderRoot(String uriStr, String rootPrefix) {
        return uriStr.startsWith(rootPrefix);
    }

    public static String rootPrefixOf(URI rootUri, boolean isFolder) {
        String s = normalizeUriString(rootUri);
        if (isFolder && !s.endsWith("/")) return s + "/";
        return s;
    }

    public static Path toLocalPath(URI uri) {
        return Paths.get(uri);
    }
}
