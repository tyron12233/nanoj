package com.tyron.nanoj.api.tasks;

import java.util.Objects;

/**
 * Class-based task definition for complex tasks.
 */
public interface TaskDefinition {

    String getId();

    default void configure(TaskBuilder builder) {
        Objects.requireNonNull(builder, "builder");
    }

    /**
     * Configure this task using a configuration-time view of the task registry.
     *
     * <p>Default implementation delegates to {@link #configure(TaskBuilder)} for backwards compatibility.
     */
    default void configure(TaskBuilder builder, TaskConfigurationContext context) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(context, "context");
        configure(builder);
    }

    void execute(TaskExecutionContext context) throws Exception;
}
