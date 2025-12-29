package com.tyron.nanoj.testFramework;

import com.tyron.nanoj.core.completion.CompletionCore;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.project.ProjectLifecycle;

/**
 * Base class for completion infrastructure tests.
 */
public abstract class BaseCompletionTest extends BaseEditorTest {

    @Override
    protected void beforeEach() throws Exception {
        super.beforeEach();

        // Completion tests should generally run in "smart" mode unless they explicitly test dumb mode.
        // Make the "indexing takes too long" dumb-mode threshold effectively unreachable for unit tests.
        project.getConfiguration().setProperty(IndexManager.DUMB_THRESHOLD_MS_KEY, "600000");

        CompletionCore.register(project);

        // Signal that the project is now fully configured.
        // Indexing (VFS listening) starts here.
        ProjectLifecycle.fireProjectOpened(project);
    }
}
