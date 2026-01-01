package com.tyron.nanoj.core.vfs;

import java.util.*;
import java.util.stream.*;

public class StreamUtils {

    /**
     * Converts a Stream<T> into a Stream<List<T>> of the given batch size.
     * This is lazy: it only consumes the source stream as needed.
     */
    public static <T> Stream<List<T>> batch(Stream<T> source, int batchSize) {
        Iterator<T> sourceIterator = source.iterator();
        
        Iterator<List<T>> batchIterator = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return sourceIterator.hasNext();
            }

            @Override
            public List<T> next() {
                List<T> batch = new ArrayList<>(batchSize);
                while (sourceIterator.hasNext() && batch.size() < batchSize) {
                    batch.add(sourceIterator.next());
                }
                return batch;
            }
        };

        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(batchIterator, Spliterator.ORDERED | Spliterator.NONNULL), 
            false // Start sequential, let the caller decide if they want parallel()
        );
    }
}