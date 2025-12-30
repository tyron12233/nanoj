package com.tyron.nanoj.api.tasks;

import java.util.Objects;

/**
 * Lightweight reference to a project by id.
 */
public final class ProjectRef {

    private final String id;

    private ProjectRef(String id) {
        this.id = id;
    }

    public static ProjectRef of(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is blank");
        }
        return new ProjectRef(id.trim());
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectRef that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ProjectRef(" + id + ")";
    }
}
