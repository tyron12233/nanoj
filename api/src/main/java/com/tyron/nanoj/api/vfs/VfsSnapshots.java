package com.tyron.nanoj.api.vfs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Utilities to take stable snapshots of VFS structures.
 */
public final class VfsSnapshots {

    private VfsSnapshots() {
    }

    public static VfsSnapshot snapshot(FileObject root, VfsSnapshot.Mode mode, boolean refresh) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(mode, "mode");

        if (refresh) {
            root.refresh();
        }

        List<VfsSnapshot.Entry> entries = new ArrayList<>();
        if (mode == VfsSnapshot.Mode.SELF) {
            entries.add(entryOf(root, refresh));
        } else {
            snapshotTree(root, refresh, entries);
        }

        entries.sort(Comparator.comparing(VfsSnapshot.Entry::getPath));
        return new VfsSnapshot(mode, root.getPath(), entries);
    }

    private static void snapshotTree(FileObject root, boolean refresh, List<VfsSnapshot.Entry> out) {
        Deque<FileObject> stack = new ArrayDeque<>();
        Set<String> visitedPaths = new HashSet<>();

        stack.push(root);

        while (!stack.isEmpty()) {
            FileObject fo = stack.pop();
            if (fo == null) continue;

            if (refresh) {
                fo.refresh();
            }

            String path = fo.getPath();
            if (!visitedPaths.add(path)) {
                continue;
            }

            out.add(entryOf(fo, false));

            if (fo.exists() && fo.isFolder()) {
                List<FileObject> children = fo.getChildren();
                // Push in reverse so natural order roughly preserved before final sort.
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i));
                }
            }
        }
    }

    private static VfsSnapshot.Entry entryOf(FileObject fo, boolean refreshBeforeRead) {
        if (refreshBeforeRead) {
            fo.refresh();
        }

        boolean exists = fo.exists();
        boolean folder = exists && fo.isFolder();
        long lm = exists ? fo.lastModified() : 0L;
        long len = exists ? fo.getLength() : 0L;

        return new VfsSnapshot.Entry(fo.getPath(), exists, folder, lm, len);
    }
}
