package com.tyron.nanoj.api.completion;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.project.Project;

/**
 * Context passed to providers to generate completion.
 */
public record CompletionParameters(Project project, FileObject file, String text, int offset) {

}