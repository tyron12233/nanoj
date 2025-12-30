package com.tyron.nanoj.api.tasks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builder for {@link TaskOptions}.
 */
public final class TaskOptionsBuilder {

    private final Map<String, String> values = new LinkedHashMap<>();

    public TaskOptionsBuilder put(String key, String value) {
        if (key == null || key.isBlank()) return this;
        values.put(key, value);
        return this;
    }

    public TaskOptionsBuilder putBoolean(String key, boolean value) {
        return put(key, Boolean.toString(value));
    }

    public TaskOptionsBuilder putInt(String key, int value) {
        return put(key, Integer.toString(value));
    }

    public TaskOptionsBuilder putLong(String key, long value) {
        return put(key, Long.toString(value));
    }

    public TaskOptions build() {
        return new TaskOptions(values);
    }
}
