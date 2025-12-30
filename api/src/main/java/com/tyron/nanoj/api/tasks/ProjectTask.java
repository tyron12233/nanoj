package com.tyron.nanoj.api.tasks;

import com.tyron.nanoj.api.project.Project;

/**
 * A {@link Task} that belongs to a specific {@link Project}.
 *
 * <p>This is primarily used to support cross-project task dependencies.
 */
public interface ProjectTask extends Task {

    Project getProject();
}
