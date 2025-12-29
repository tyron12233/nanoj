package com.tyron.nanoj.lang.java.source;

public class CancellationException extends RuntimeException {

    public CancellationException() {
        super("");
    }

    public CancellationException(String message) {
        super(message);
    }
}
