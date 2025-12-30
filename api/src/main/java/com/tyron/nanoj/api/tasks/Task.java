package com.tyron.nanoj.api.tasks;

import java.util.List;

public interface Task {
    String getId();

    boolean isCacheable();

    List<TaskOutput> getOutputs();

    default TaskOutput output(int index) {
        return getOutputs().get(index);
    }
}
