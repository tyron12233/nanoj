package com.tyron.nanoj.api.tasks;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Project-scoped task graph runner with incremental caching.
 */
public interface TasksService {

    static TasksService getInstance(Project project) {
        Objects.requireNonNull(project, "project");
        return project.getService(TasksService.class);
    }

    /**
     * @return the owning project for this task service.
     */
    Project getProject();

    /**
     * Resolves an output produced by a task in another project by ids.
     *
     * <p>This is a convenience for cross-project wiring when you don't have a {@link Project} or {@link Task}
     * reference at configuration time.
     */
    default TaskOutput output(String projectId, String taskId, int outputIndex) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is blank");
        }
        Project project = ProjectRegistry.getInstance().requireProject(projectId);
        TasksService tasks = TasksService.getInstance(project);
        Task task = tasks.getTask(taskId.trim());
        if (task == null) {
            throw new IllegalStateException("Task not registered: " + taskId + " (projectId=" + projectId + ")");
        }
        return task.output(outputIndex);
    }

    default TaskOutput output(ProjectRef project, String taskId, int outputIndex) {
        Objects.requireNonNull(project, "project");
        return output(project.getId(), taskId, outputIndex);
    }

    /**
     * Starts a fluent definition for a task with the given id.
     * If a task with the same id already exists, it is replaced.
     */
    TaskBuilder task(String id);

    /**
     * Resolves a path to a VFS {@link FileObject}.
     *
     * <p>Implementations should support both project-relative paths and absolute paths/URIs.
     */
    FileObject file(String path);

    /**
     * Alias of {@link #file(String)} intended for readability when the path represents a directory.
     */
    default FileObject dir(String path) {
        return file(path);
    }

    /**
     * Convenience for resolving multiple paths.
     */
    default FileObject[] files(String... paths) {
        if (paths == null || paths.length == 0) return new FileObject[0];
        FileObject[] out = new FileObject[paths.length];
        for (int i = 0; i < paths.length; i++) {
            out[i] = file(paths[i]);
        }
        return out;
    }

    /**
     * Convenience for class-based task definitions.
     */
    default Task register(TaskDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        TaskBuilder builder = task(definition.getId());
        definition.configure(builder, new TaskConfigurationContext() {
            @Override
            public Project getProject() {
                return TasksService.this.getProject();
            }

            @Override
            public Task getTask(String id) {
                return TasksService.this.getTask(id);
            }

            @Override
            public FileObject file(String path) {
                return TasksService.this.file(path);
            }
        });
        return builder.register(definition::execute);
    }

    /**
     * Executes the given task and its dependencies.
     */
    TaskRun run(Task task);

    /**
     * Executes the given task and its dependencies asynchronously.
     */
    CompletableFuture<TaskRun> runAsync(Task task);

    /**
     * Looks up a previously registered task by id.
     */
    Task getTask(String id);

    /**
     * Adds a listener that receives task lifecycle callbacks.
     */
    void addListener(TaskExecutionListener listener);

    /**
     * Removes a previously added listener.
     */
    void removeListener(TaskExecutionListener listener);
}
