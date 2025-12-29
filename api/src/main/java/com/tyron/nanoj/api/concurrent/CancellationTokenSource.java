package com.tyron.nanoj.api.concurrent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Source for a {@link CancellationToken}.
 */
public final class CancellationTokenSource {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CancellationToken token = cancelled::get;

    public CancellationToken token() {
        return token;
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public static CancellationTokenSource cancelled() {
        CancellationTokenSource s = new CancellationTokenSource();
        s.cancel();
        return s;
    }
}
