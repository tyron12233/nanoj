package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import com.tyron.nanoj.api.indexing.IndexManager;

import java.util.ArrayList;
import java.util.List;

public class IndexingInputCollectorTest extends BaseIdeTest {

    @Test
    public void collectAll_onlySourceRoots_buildDirExcluded() {
        configureJavaProject();

        // Create and register build dir, then (intentionally) add it as a source root to ensure exclusion works.
        MockFileObject buildDir = new MockFileObject(project.getRootDirectory().getPath() + "/build", "");
        buildDir.setFolder(true);
        testVfs.registerFile(buildDir);
        project.setBuildDirectory(buildDir);
        project.addSourceRoot(buildDir);

        IndexManager manager = IndexManager.getInstance();

        List<String> paths = new ArrayList<>();
        for (var fo : new IndexingInputCollector(project)) {
            if (fo == null) continue;
            try {
                paths.add(fo.getPath());
            } catch (Throwable ignored) {
            }
        }

        String srcRoot = project.getSourceRoots().get(0).getPath();

        Assertions.assertTrue(paths.contains(srcRoot), paths.toString());
        Assertions.assertFalse(paths.contains(buildDir.getPath()), paths.toString());

        // Policy: only source roots (no project root).
        Assertions.assertFalse(paths.contains(project.getRootDirectory().getPath()), paths.toString());
    }
}
