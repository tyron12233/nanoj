package com.tyron.nanoj.core.vfs.persistent;

import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.mapdb.DBException;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * MapDB-backed persistent storage used by VFS (file-ids + snapshot records).
 */
public final class PersistentVfsStorage implements Closeable {

    private final DB db;

    public final HTreeMap<String, Integer> uriToId;
    public final HTreeMap<Integer, String> idToUri;
    public final HTreeMap<String, Integer> fileKeyToId;

    /**
     * Snapshot state for all tracked URIs, ordered by URI string.
     * Use prefix range queries for per-root diffs.
     */
    public final BTreeMap<String, PersistentVfsRecord> recordsByUri;

    /**
     * Roots (URI prefixes) that should be synced on refresh.
     */
    public final HTreeMap<String, Boolean> trackedRoots;

    public final org.mapdb.Atomic.Integer nextId;

    public PersistentVfsStorage(Path dbFile) {
        Objects.requireNonNull(dbFile, "dbFile");
        try {
            Files.createDirectories(dbFile.toAbsolutePath().normalize().getParent());
        } catch (Exception ignored) {
            // Best-effort.
        }

        this.db = openDb(dbFile);

        this.nextId = db.atomicInteger("vfs.nextId").createOrOpen();

        this.uriToId = db.hashMap("vfs.uriToId", Serializer.STRING, Serializer.INTEGER).createOrOpen();
        this.idToUri = db.hashMap("vfs.idToUri", Serializer.INTEGER, Serializer.STRING).createOrOpen();
        this.fileKeyToId = db.hashMap("vfs.fileKeyToId", Serializer.STRING, Serializer.INTEGER).createOrOpen();

        this.recordsByUri = db.treeMap("vfs.recordsByUri", Serializer.STRING, Serializer.JAVA).createOrOpen();
        this.trackedRoots = db.hashMap("vfs.trackedRoots", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
    }

    private static DB openDb(Path dbFile) {
        try {
            return DBMaker
                    .fileDB(dbFile.toFile())
                    .fileMmapEnableIfSupported()
                    .checksumHeaderBypass()
                    .closeOnJvmShutdown()
                    .make();
        } catch (DBException.FileLocked locked) {
            // In tests (and some dev scenarios), multiple JVMs may run concurrently.
            // Falling back to an in-memory DB keeps the IDE/test process functional.
            if (isTestEnvironment() || Boolean.parseBoolean(System.getProperty("nanoj.vfs.allowInMemoryFallback", "false"))) {
                return DBMaker.memoryDB().closeOnJvmShutdown().make();
            }
            throw locked;
        }
    }

    private static boolean isTestEnvironment() {
        if (System.getProperty("org.gradle.test.worker") != null) {
            return true;
        }
        String cp = System.getProperty("java.class.path", "");
        String lower = cp.toLowerCase();
        return lower.contains("junit") || lower.contains("surefire") || lower.contains("testng");
    }

    public void commit() {
        db.commit();
    }

    @Override
    public void close() {
        db.close();
    }
}
