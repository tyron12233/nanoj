package com.tyron.nanoj.testFramework;

import java.time.Duration;
import java.time.Instant;

public class Stopwatch {

    public static Stopwatch start() {
        return new Stopwatch(Instant.now());
    }

    private final Instant start;
    private Instant end;

    private Stopwatch(Instant start) {
        this.start = start;
    }

    public void end() {
        end = Instant.now();
    }

    public void log(String prefix) {
        end();
        System.out.println(prefix + Duration.between(start, end).toMillis());
    }
}
