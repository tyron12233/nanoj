package com.tyron.nanoj.lang.java.search;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.lang.java.indexing.ShortClassNameIndex;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.core.test.MockProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class ClassCompletionServiceTest {

    private File cacheDir;
    private Project project;
    private IndexManager indexManager;
    private ClassCompletionService completionService;

    @BeforeEach
    public void setUp() throws IOException {
        cacheDir = Files.createTempDirectory("completion_test").toFile();
        project = new MockProject(cacheDir);

        indexManager = new IndexManager(project);
        ProjectServiceManager.registerInstance(project, IndexManager.class, indexManager);

        ShortClassNameIndex indexDef = new ShortClassNameIndex(project);
        ProjectServiceManager.registerInstance(project, ShortClassNameIndex.class, indexDef);
        indexManager.register(indexDef);

        completionService = new ClassCompletionService(project);
    }

    @AfterEach
    public void tearDown() {
        indexManager.dispose();
        deleteRecursive(cacheDir);
    }

    @Test
    public void testPrefixCompletion() throws InterruptedException {
        // Setup environment
        MockFileObject f1 = new MockFileObject("/List.java", "package java.util; public interface List {}");
        MockFileObject f2 = new MockFileObject("/LinkedList.java", "package java.util; public class LinkedList {}");
        MockFileObject f3 = new MockFileObject("/Linear.java", "package android.widget; public class LinearLayout {}");
        
        indexManager.updateFile(f1);
        indexManager.updateFile(f2);
        indexManager.updateFile(f3);

        indexManager.flush();

        // act: Search for "Li"
        List<String> results = completionService.findClasses("Li");

        // Assert
        Assertions.assertEquals(3, results.size());
        Assertions.assertTrue(results.contains("java.util.List"));
        Assertions.assertTrue(results.contains("java.util.LinkedList"));
        Assertions.assertTrue(results.contains("android.widget.LinearLayout"));
    }

    @Test
    public void testUpdatesReflectInCompletion() throws InterruptedException {
        MockFileObject file = new MockFileObject("/Temp.java", "package test; class TempClass {}");
        
        indexManager.updateFile(file);
        indexManager.flush();
        
        Assertions.assertEquals(1, completionService.findClasses("Temp").size());

        // rename class
        file.setContent("package test; class RenamedClass {}");
        indexManager.updateFile(file);
        indexManager.flush();

        // Should NOT find "Temp", should find "Renamed"
        Assertions.assertTrue(completionService.findClasses("Temp").isEmpty());
        Assertions.assertFalse(completionService.findClasses("Renamed").isEmpty());
    }

    @Test
    public void testNoResultsForBadPrefix() throws InterruptedException {
        MockFileObject f1 = new MockFileObject("/A.java", "class Alpha {}");
        indexManager.updateFile(f1);
        indexManager.flush();

        Assertions.assertTrue(completionService.findClasses("Beta").isEmpty());
    }

    private void deleteRecursive(File f) { if(f.isDirectory()) for(File c:f.listFiles()) deleteRecursive(c); f.delete(); }
}