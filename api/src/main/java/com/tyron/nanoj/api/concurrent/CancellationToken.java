package com.tyron.nanoj.api.concurrent;

import java.util.concurrent.CancellationException;

/**
 * Cooperative cancellation token.
 *
 * Tasks should periodically call {@link #throwIfCancelled()} and stop promptly.
 */
public interface CancellationToken {

    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException();
        }
    }
}
