package com.tyron.nanoj.api.tasks;

import java.util.Map;

public interface TaskRun {

    Map<String, TaskResult> getResults();

    default TaskResult getResult(Task task) {
        return getResults().get(task.getId());
    }
}
