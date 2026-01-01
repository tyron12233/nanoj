package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;
import org.jspecify.annotations.NonNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A lazy iterator that traverses the FileObject tree but ONLY returns files (leaves).
 * Directories are traversed to find children but are never returned by next().
 */
public class FileObjectTreeIterator implements Iterator<FileObject>, Iterable<FileObject> {

    private final Deque<FileObject> stack = new ArrayDeque<>();
    private FileObject nextFile;

    public FileObjectTreeIterator(FileObject root) {
        if (root != null) {
            stack.push(root);
        }
        advance();
    }

    /**
     * Loops through the stack until it finds a File (not a folder)
     * or runs out of items.
     */
    private void advance() {
        nextFile = null;

        while (!stack.isEmpty()) {
            FileObject current = stack.pop();

            if (current.isFolder()) {
                List<FileObject> children = current.getChildren();
                if (children != null) {
                    for (int i = children.size() - 1; i >= 0; i--) {
                        stack.push(children.get(i));
                    }
                }
            } else {
                nextFile = current;
                return;
            }
        }
    }

    @Override
    public boolean hasNext() {
        return nextFile != null;
    }

    @Override
    public FileObject next() {
        if (nextFile == null) {
            throw new NoSuchElementException();
        }

        FileObject result = nextFile;
        advance();
        return result;
    }

    @Override
    public @NonNull Iterator<FileObject> iterator() {
        return this;
    }

    /**
     * Converts to a Stream for easy filtering/mapping.
     */
    public Stream<FileObject> stream() {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(this, Spliterator.ORDERED | Spliterator.NONNULL),
                false
        );
    }
}