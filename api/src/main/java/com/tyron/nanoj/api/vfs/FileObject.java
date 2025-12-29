package com.tyron.nanoj.api.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Abstraction over a file system entry (File, ZipEntry, Memory).
 * <p>
 * This isolates the IDE core from the underlying storage mechanism (Disk vs Jar vs Ram).
 * Path separators should always be normalized to forward slashes '/'.
 */
public interface FileObject {

    /**
     * @return The file name with extension (e.g., "Main.java").
     */
    String getName();

    /**
     * @return The extension without the dot (e.g., "java"), or empty string if none.
     */
    String getExtension();

    /**
     * @return The absolute path to this file.
     */
    String getPath();

    /**
     * @return A URI representing this file (e.g., file:///sdcard/..., jar:file:///...).
     */
    URI toUri();

    // --- Hierarchy ---

    /**
     * @return The parent folder, or null if this is the root.
     */
    FileObject getParent();

    /**
     * @return A list of children if this is a folder, otherwise empty.
     */
    List<FileObject> getChildren();

    /**
     * Retrieves a specific child.
     * @param name The name of the child.
     * @return The child FileObject (which might not exist yet).
     */
    FileObject getChild(String name);

    // --- Attributes ---

    boolean exists();

    boolean isFolder();

    boolean isReadOnly();

    long lastModified();

    long getLength();

    // --- IO Operations ---

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    /**
     * Convenience method to read entire file as bytes.
     */
    default byte[] getContent() throws IOException {
        try (InputStream in = getInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * Convenience method to read file as UTF-8 string.
     */
    default String getText() throws IOException {
        return new String(getContent(), StandardCharsets.UTF_8);
    }

    // --- Mutation ---

    /**
     * Creates a new file under this folder.
     * @throws UnsupportedOperationException if this is not a folder.
     */
    FileObject createFile(String name) throws IOException;

    /**
     * Creates a new directory under this folder.
     * @throws UnsupportedOperationException if this is not a folder.
     */
    FileObject createFolder(String name) throws IOException;

    void delete() throws IOException;

    void rename(String newName) throws IOException;

    /**
     * Forces a reload from the underlying filesystem (e.g. disk check).
     */
    void refresh();
}