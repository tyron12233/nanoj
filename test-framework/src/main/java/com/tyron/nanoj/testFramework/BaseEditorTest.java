package com.tyron.nanoj.testFramework;

import com.tyron.nanoj.core.editor.EditorCore;

/**
 * Base class for editor/document infrastructure tests.
 */
public abstract class BaseEditorTest extends BaseIdeTest {

    @Override
    protected void beforeEach() throws Exception {
        super.beforeEach();
        EditorCore.register(project);
    }
}
