package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Stream;

/**
 * High-level operations for the Virtual File System.
 */
public class FileUtil {

    /**
     * Copies a file or directory recursively.
     *
     * @param source The source file or folder.
     * @param targetFolder The destination **parent** folder.
     * @return The newly created file/folder.
     */
    public static FileObject copy(FileObject source, FileObject targetFolder) throws IOException {
        if (source.isFolder()) {
            FileObject newDir = targetFolder.createFolder(source.getName());
            for (FileObject child : source.getChildren()) {
                copy(child, newDir);
            }
            return newDir;
        } else {
            FileObject newFile = targetFolder.createFile(source.getName());
            copyStream(source, newFile);
            return newFile;
        }
    }

    /**
     * Recursively deletes a file or directory.
     * Note: {@link FileObject#delete()} on NIO typically fails if a directory is not empty.
     * This utility handles the children first.
     */
    public static void deleteRecursively(FileObject file) throws IOException {
        if (!file.exists()) return;

        if (file.isFolder()) {
            // Stack-based traversal to avoid recursion depth issues on deep trees
            // Ideally, we delete children first.
            // Simple recursive approach for clarity:
            for (FileObject child : file.getChildren()) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    /**
     * Efficiently copies content from one file to another.
     */
    public static void copyStream(FileObject src, FileObject dest) throws IOException {
        try (InputStream in = src.getInputStream();
             OutputStream out = dest.getOutputStream()) {
            byte[] buffer = new byte[8192]; // 8KB buffer
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Gets the relative path of 'file' with respect to 'base'.
     * Returns null if 'file' is not inside 'base'.
     */
    public static String getRelativePath(FileObject base, FileObject file) {
        String basePath = base.getPath();
        String filePath = file.getPath();

        if (!basePath.endsWith("/")) basePath += "/";

        if (filePath.startsWith(basePath)) {
            return filePath.substring(basePath.length());
        }
        return null; // Not a child
    }

    public static Stream<FileObject> childrenStream(FileObject dir) {
        return new FileObjectTreeIterator(dir).stream();
    }

    public static List<FileObject> collectChildren(FileObject dir) {
        if (!dir.isFolder()) {
            return List.of();
        }
        Set<FileObject> visited = new HashSet<>();
        Queue<FileObject> queue = new ArrayDeque<>();
        List<FileObject> files = new LinkedList<>();

        queue.add(dir);

        while (!queue.isEmpty()) {
            FileObject poll = queue.poll();
            if (visited.contains(poll)) {
                continue;
            }

            List<FileObject> children = poll.getChildren();
            for (FileObject child : children) {
                if (child.isFolder()) {
                    queue.add(child);
                } else {
                    files.add(child);
                }
            }

            visited.add(poll);
        }

        return files;
    }
}