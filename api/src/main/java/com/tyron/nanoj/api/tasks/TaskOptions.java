package com.tyron.nanoj.api.tasks;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable task options.
 * <p>
 * Options participate in up-to-date checks.
 */
public final class TaskOptions {

    private final Map<String, String> values;

    TaskOptions(Map<String, String> values) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public String get(String key) {
        return values.get(key);
    }

    public String get(String key, String defaultValue) {
        String v = values.get(key);
        return v != null ? v : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v);
    }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
