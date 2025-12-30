package com.tyron.nanoj.api.tasks;

@FunctionalInterface
public interface TaskAction {
    void execute(TaskExecutionContext context) throws Exception;
}
