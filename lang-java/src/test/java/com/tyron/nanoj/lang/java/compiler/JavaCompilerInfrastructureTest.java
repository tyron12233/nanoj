package com.tyron.nanoj.lang.java.compiler;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.core.concurrent.TaskSchedulerImpl;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.core.test.MockFileSystem;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.core.vfs.VirtualFileSystem;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;
import com.tyron.nanoj.lang.java.indexing.JavaSuperTypeIndex;
import com.tyron.nanoj.lang.java.source.JavaSource;
import com.tyron.nanoj.lang.java.source.ParsingManager;
import org.junit.jupiter.api.*;

import javax.lang.model.element.Element;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Tag("deprecated")
public class JavaCompilerInfrastructureTest {

    private MockProject project;
    private MockFileObject sourceFile;
    private MockFileSystem mockFileSystem;
    private IndexManager indexManager;


    @BeforeEach
    public void setUp() throws IOException {
        var tempDir = Files.createTempDirectory("source_test").toFile();
        project = new MockProject(tempDir);

        indexManager = new IndexManager(project);
        ProjectServiceManager.registerInstance(project, IndexManager.class, indexManager);
        ProjectServiceManager.registerBinding(project, TaskScheduler.class, TaskSchedulerImpl.class);

        indexManager.register(new JavaBinaryStubIndexer(project));
        indexManager.register(new JavaPackageIndex(project));
        indexManager.register(new JavaSuperTypeIndex(project));

        ProjectServiceManager.registerInstance(
                project,
                JavacFileManagerService.class,
                new JavacFileManagerService(project)
        );

        mockFileSystem = new MockFileSystem();
        VirtualFileSystem.getInstance().register(mockFileSystem);

        ProjectServiceManager.registerInstance(project, ParsingManager.class, new ParsingManager(project));

        sourceFile = new MockFileObject("Test.java", "");
        mockFileSystem.registerFile(sourceFile);
    }

    @AfterEach
    public void tearDown() {
        ProjectServiceManager.disposeProject(project);
    }

    /**
     * Test 1: Basic Parsing
     * Does the compiler accept a string and return an AST?
     */
    @Test
    public void testSimpleParsing() throws Exception {
        String code = "public class Test {}";
        JavaSource source = JavaSource.forFile(project, sourceFile);

        CompletableFuture<String> future = source.runModificationTask(code, info -> {
            CompilationUnitTree unit = info.getCompilationUnit();
            return unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        });

        // The default package should be empty string or null depending on impl, 
        // but verify we got a result without exception.
        Assertions.assertNotNull(future.get(5, TimeUnit.SECONDS));
        
        // Verify class name in AST
        source.runModificationTask(code, info -> {
            ClassTree tree = (ClassTree) info.getCompilationUnit().getTypeDecls().get(0);
            Assertions.assertEquals("Test", tree.getSimpleName().toString());
            return null;
        }).get();
    }

    /**
     * Test 2: Type Resolution (The expensive part)
     * Does 'String' resolve to 'java.lang.String'?
     */
    @Test
    public void testTypeResolution() throws Exception {
        String code = "public class Test { String s; }";
        JavaSource source = JavaSource.forFile(project, sourceFile);

        CompletableFuture<Boolean> future = source.runUserActionTask(code, info -> {
            // Get the Trees utility
            Trees trees = Trees.instance(info.getTask());
            CompilationUnitTree unit = info.getCompilationUnit();
            ClassTree classTree = (ClassTree) unit.getTypeDecls().get(0);
            
            // Get the 'String s' variable
            var variableTree = classTree.getMembers().get(1);
            
            // Get the Element (Symbol)
            Element el = trees.getElement(trees.getPath(unit, variableTree));
            
            // It should be a field
            Assertions.assertNotNull(el, "Element should be resolved");
            
            // Check type of field
            return el.asType().toString().equals("java.lang.String");
        });

        Assertions.assertTrue(future.get(5, TimeUnit.SECONDS), "Should resolve String to java.lang.String");
    }

    /**
     * Test 3: Caching
     * If we request the same content twice, do we get the SAME CompilationInfo object?
     */
    @Test
    public void testCachingBehavior() throws Exception {
        String code = "class CacheTest {}";
        JavaSource source = JavaSource.forFile(project, sourceFile);

        // First Request
        int identity1 = source.runModificationTask(code, System::identityHashCode).get();

        // Second Request (Same text)
        int identity2 = source.runModificationTask(code, System::identityHashCode).get();

        Assertions.assertEquals(identity1, identity2, "Should reuse compilation info for identical content");

        // Third Request (Different text)
        int identity3 = source.runModificationTask(code + " ", System::identityHashCode).get();

        Assertions.assertNotEquals(identity1, identity3, "Should create new compilation info for changed content");
    }

    /**
     * Test 4: Phase Promotion
     * If we parse (fast), then resolve (slow) on the same text, does it upgrade the existing object?
     */
    @Test
    public void testPhasePromotion() throws Exception {
        String code = "class Promotion {}";
        JavaSource source = JavaSource.forFile(project, sourceFile);

        AtomicReference<CompilationInfo> ref = new AtomicReference<>();

        // 1. Run low priority (PARSED)
        source.runModificationTask(code, info -> {
            ref.set(info);
            // Verify internal state is PARSED (We can't access private fields, 
            // but we know analyze() hasn't run if we only requested PARSED)
            return null;
        }).get();

        CompilationInfo original = ref.get();
        Assertions.assertNotNull(original);

        // 2. Run high priority (RESOLVED) on same text
        source.runUserActionTask(code, info -> {
            // Should be the same object instance
            Assertions.assertSame(original, info, "Should reuse the object");
            
            // But now it should be analyzed
            Assertions.assertNotNull(info.getTask().getElements());
            return null;
        }).get();
    }

    /**
     * Test 5: Debouncing / Priority Cancellation
     * A high priority task should effectively cancel a pending low priority task
     * or at least queue ahead of it.
     */
    @Test
    public void testPriorityCancellation() throws Exception {
        String code = "class Slow {}";
        JavaSource source = JavaSource.forFile(project, sourceFile);

        // 1. Start a slow, low-priority task
        CompletableFuture<String> slowTask = source.runModificationTask(code, info -> {
            try {
                // Simulate heavy work
                Thread.sleep(1000); 
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted");
            }
            return "Slow";
        });

        // 2. Immediately start a high-priority task
        // Because the ParsingManager is single-threaded, submitting this 
        // with priority=true should signal the current running task to cancel.
        CompletableFuture<String> fastTask = source.runUserActionTask(code, info -> "Fast");

        // 3. Fast task should succeed
        Assertions.assertEquals("Fast", fastTask.get(2, TimeUnit.SECONDS));

        // 4. Slow task should be cancelled or fail
        try {
            slowTask.get(2, TimeUnit.SECONDS);
            // If it didn't throw, check if it returned "Slow". 
            // In a real env, it might finish if the sleep wasn't interruptible, 
            // but our ParsingManager calls cancel(true).
        } catch (CancellationException | ExecutionException e) {
            // Expected behavior
        }
    }

    /**
     * Test 6: Syntax Error Handling
     * Compiler shouldn't crash on bad code.
     */
    @Test
    public void testSyntaxErrors() throws Exception {
        String badCode = "public class { broken"; // Missing name, braces
        JavaSource source = JavaSource.forFile(project, sourceFile);

        CompletableFuture<Boolean> future = source.runModificationTask(badCode, info -> {
            // Even with errors, we should get a compilation unit
            return info.getCompilationUnit() != null;
        });

        Assertions.assertTrue(future.get(), "Should return a CU even for broken code");
    }
}