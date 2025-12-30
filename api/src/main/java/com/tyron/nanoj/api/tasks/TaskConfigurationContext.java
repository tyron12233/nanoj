package com.tyron.nanoj.api.tasks;

import com.tyron.nanoj.api.project.Project;

import java.util.Objects;

/**
 * Configuration-time view of the current task registry.
 *
 * <p>Intended for wiring task dependencies/inputs via ids inside {@link TaskDefinition#configure(TaskBuilder, TaskConfigurationContext)}.
 */
public interface TaskConfigurationContext {

    Project getProject();

    /**
     * Looks up a previously registered task by id.
     *
     * @return the task, or {@code null} if not found
     */
    Task getTask(String id);

    /**
     * Resolves a path to a VFS {@link com.tyron.nanoj.api.vfs.FileObject}.
     */
    com.tyron.nanoj.api.vfs.FileObject file(String path);

    /**
     * Alias of {@link #file(String)} intended for directory-like paths.
     */
    default com.tyron.nanoj.api.vfs.FileObject dir(String path) {
        return file(path);
    }

    /**
     * Convenience for resolving multiple paths.
     */
    default com.tyron.nanoj.api.vfs.FileObject[] files(String... paths) {
        if (paths == null || paths.length == 0) return new com.tyron.nanoj.api.vfs.FileObject[0];
        com.tyron.nanoj.api.vfs.FileObject[] out = new com.tyron.nanoj.api.vfs.FileObject[paths.length];
        for (int i = 0; i < paths.length; i++) {
            out[i] = file(paths[i]);
        }
        return out;
    }

    /**
     * Looks up a previously registered task by id.
     *
     * @throws IllegalStateException if the task does not exist
     */
    default Task requireTask(String id) {
        Objects.requireNonNull(id, "id");
        Task task = getTask(id);
        if (task == null) {
            throw new IllegalStateException("Task not registered: " + id);
        }
        return task;
    }

    /**
     * Looks up an output from a previously registered task.
     *
     * @throws IllegalStateException if the task does not exist
     * @throws IndexOutOfBoundsException if the output index is invalid
     */
    default TaskOutput requireOutput(String taskId, int outputIndex) {
        Task task = requireTask(taskId);
        if (outputIndex < 0 || outputIndex >= task.getOutputs().size()) {
            throw new IndexOutOfBoundsException("Task '" + taskId + "' has " + task.getOutputs().size()
                    + " outputs; requested index " + outputIndex);
        }
        return task.output(outputIndex);
    }

    /**
     * Resolves an output produced by another project's task by ids.
     */
    default TaskOutput output(String projectId, String taskId, int outputIndex) {
        Project project = ProjectRegistry.getInstance().requireProject(projectId);
        TasksService tasks = TasksService.getInstance(project);
        Task t = tasks.getTask(taskId);
        if (t == null) {
            throw new IllegalStateException("Task not registered: " + taskId + " (projectId=" + projectId + ")");
        }
        return t.output(outputIndex);
    }

    default TaskOutput output(ProjectRef project, String taskId, int outputIndex) {
        Objects.requireNonNull(project, "project");
        return output(project.getId(), taskId, outputIndex);
    }
}
