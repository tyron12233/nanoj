package com.tyron.nanoj.api.tasks;

import com.tyron.nanoj.api.vfs.FileObject;

public interface TaskOutput {
    Task getTask();

    FileObject getFile();
}
