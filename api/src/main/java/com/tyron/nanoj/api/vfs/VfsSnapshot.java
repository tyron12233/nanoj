package com.tyron.nanoj.api.vfs;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A stable, ordered snapshot of one file or a folder tree.
 */
public final class VfsSnapshot {

    public enum Mode {
        SELF,
        TREE
    }

    public static final class Entry {
        private final String path;
        private final boolean exists;
        private final boolean folder;
        private final long lastModified;
        private final long length;

        public Entry(String path, boolean exists, boolean folder, long lastModified, long length) {
            this.path = Objects.requireNonNull(path, "path");
            this.exists = exists;
            this.folder = folder;
            this.lastModified = lastModified;
            this.length = length;
        }

        public String getPath() {
            return path;
        }

        public boolean exists() {
            return exists;
        }

        public boolean isFolder() {
            return folder;
        }

        public long getLastModified() {
            return lastModified;
        }

        public long getLength() {
            return length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Entry that)) return false;
            return exists == that.exists
                    && folder == that.folder
                    && lastModified == that.lastModified
                    && length == that.length
                    && path.equals(that.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, exists, folder, lastModified, length);
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "path='" + path + '\'' +
                    ", exists=" + exists +
                    ", folder=" + folder +
                    ", lastModified=" + lastModified +
                    ", length=" + length +
                    '}';
        }
    }

    private final Mode mode;
    private final String rootPath;
    private final List<Entry> entries;

    public VfsSnapshot(Mode mode, String rootPath, List<Entry> entries) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.rootPath = Objects.requireNonNull(rootPath, "rootPath");
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public Mode getMode() {
        return mode;
    }

    public String getRootPath() {
        return rootPath;
    }

    /**
     * Entries are ordered by {@link Entry#getPath()}.
     */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
