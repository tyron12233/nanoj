package com.tyron.nanoj.api.tasks;

import com.tyron.nanoj.api.project.Project;

import java.util.Map;

public interface TaskExecutionContext {
    Project getProject();

    Task getTask();

    /**
     * Task options that participate in the up-to-date check.
     */
    TaskOptions getOptions();

    /**
     * Compatibility view for older call sites.
     */
    default Map<String, String> getParameters() {
        return getOptions().asMap();
    }
}
