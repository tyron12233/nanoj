package com.tyron.nanoj.api.vfs;

/**
 * A file rename event.
 *
 * {@link #getFile()} returns the "new" file.
 */
public final class FileRenameEvent extends FileEvent {

    private final FileObject oldFile;

    public FileRenameEvent(FileObject oldFile, FileObject newFile) {
        super(newFile);
        this.oldFile = oldFile;
    }

    public FileObject getOldFile() {
        return oldFile;
    }
}
