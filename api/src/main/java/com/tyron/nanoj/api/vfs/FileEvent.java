package com.tyron.nanoj.api.vfs;

/**
 * Represents a change in the file system.
 */
public class FileEvent {
    private final FileObject file;

    public FileEvent(FileObject file) {
        this.file = file;
    }

    public FileObject getFile() {
        return file;
    }
}