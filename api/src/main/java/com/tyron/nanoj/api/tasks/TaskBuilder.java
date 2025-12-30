package com.tyron.nanoj.api.tasks;

import com.tyron.nanoj.api.vfs.FileObject;

import java.util.function.Consumer;

public interface TaskBuilder {

    /**
     * Controls how inputs/outputs are fingerprinted for up-to-date checks.
     */
    TaskBuilder fingerprintMode(TaskFingerprintMode mode);

    TaskBuilder dependsOn(Task... tasks);

    TaskBuilder inputs(FileObject... files);

    /**
     * Adds folder/file inputs as a recursive tree snapshot. For folders this includes all descendants.
     */
    TaskBuilder inputTrees(FileObject... roots);

    /**
     * Adds an input that is produced by another task. Also implies a dependency.
     */
    TaskBuilder inputs(TaskOutput... outputs);

    TaskBuilder outputs(FileObject... files);

    /**
     * Adds folder/file outputs as a recursive tree snapshot. For folders this includes all descendants.
     */
    TaskBuilder outputTrees(FileObject... roots);

    /**
     * Adds an option that participates in up-to-date checks.
     */
    TaskBuilder option(String key, String value);

    /**
     * Bulk configure options.
     */
    TaskBuilder options(Consumer<TaskOptionsBuilder> options);

    /**
     * Compatibility alias.
     */
    default TaskBuilder param(String key, String value) {
        return option(key, value);
    }

    /**
     * Enables/disables incremental caching for this task.
     */
    TaskBuilder cacheable(boolean cacheable);

    /**
     * Registers (or replaces) the task definition.
     */
    Task register(TaskAction action);
}
