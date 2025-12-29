package com.tyron.nanoj.lang.java.source;
public interface CancellableTask<P> extends Task<P> {

    void cancel();
}
