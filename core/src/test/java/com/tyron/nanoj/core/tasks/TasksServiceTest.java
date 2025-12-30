package com.tyron.nanoj.core.tasks;

import com.tyron.nanoj.api.tasks.Task;
import com.tyron.nanoj.api.tasks.TaskConfigurationContext;
import com.tyron.nanoj.api.tasks.TaskDefinition;
import com.tyron.nanoj.api.tasks.TaskFingerprintMode;
import com.tyron.nanoj.api.tasks.ProjectRegistry;
import com.tyron.nanoj.api.tasks.TaskResult;
import com.tyron.nanoj.api.tasks.TasksService;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.core.vfs.VirtualFileManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

public class TasksServiceTest {

    private TasksServiceImpl tasks;

    @AfterEach
    public void tearDown() {
        if (tasks != null) {
            tasks.dispose();
            tasks = null;
        }
    }

    @Test
    public void testUpToDateSkipsAndInputChangeReRuns() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_test");
        Path cacheDir = Files.createTempDirectory("nanoj_tasks_cache");

        MockProject project = new MockProject(cacheDir.toFile());
        tasks = new TasksServiceImpl(project);

        FileObject in = vfsFile(tempDir.resolve("in.txt").toFile());
        FileObject out = vfsFile(tempDir.resolve("out.txt").toFile());

        writeText(in, "A");

        AtomicInteger runs = new AtomicInteger();
        Task t = tasks.task("t")
                .inputs(in)
                .outputs(out)
                .register(ctx -> {
                    runs.incrementAndGet();
                    writeText(out, in.getText());
                });

        var run1 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run1.getResult(t).getStatus());
        Assertions.assertEquals(1, runs.get());
        Assertions.assertTrue(out.exists());
        Assertions.assertEquals("A", out.getText());

        var run2 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, run2.getResult(t).getStatus());
        Assertions.assertEquals(1, runs.get());

        writeText(in, "B");

        var run3 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run3.getResult(t).getStatus());
        Assertions.assertEquals(2, runs.get());
        Assertions.assertEquals("B", out.getText());

        deleteRecursive(tempDir);
        deleteRecursive(cacheDir);
    }

    @Test
    public void testInputTreesDetectsChildChanges() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_tree");
        Path cacheDir = Files.createTempDirectory("nanoj_tasks_tree_cache");

        MockProject project = new MockProject(cacheDir.toFile());
        tasks = new TasksServiceImpl(project);

        FileObject folder = vfsFile(tempDir.resolve("folder").toFile());
        // Ensure folder exists
        Files.createDirectories(Path.of(folder.getPath()));

        FileObject child = vfsFile(tempDir.resolve("folder/child.txt").toFile());
        FileObject out = vfsFile(tempDir.resolve("out.txt").toFile());

        writeText(child, "A");

        AtomicInteger runs = new AtomicInteger();
        Task t = tasks.task("tree")
                .inputTrees(folder)
                .outputs(out)
                .register(ctx -> {
                    runs.incrementAndGet();
                    writeText(out, Integer.toString(runs.get()));
                });

        var run1 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run1.getResult(t).getStatus());
        Assertions.assertEquals(1, runs.get());

        var run2 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, run2.getResult(t).getStatus());
        Assertions.assertEquals(1, runs.get());

        // Modify child file; should rerun
        writeText(child, "B");

        var run3 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run3.getResult(t).getStatus());
        Assertions.assertEquals(2, runs.get());

        deleteRecursive(tempDir);
        deleteRecursive(cacheDir);
    }

    @Test
    public void testClassBasedTaskDefinition() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_def");
        Path cacheDir = Files.createTempDirectory("nanoj_tasks_def_cache");

        MockProject project = new MockProject(cacheDir.toFile());
        tasks = new TasksServiceImpl(project);

        FileObject in = vfsFile(tempDir.resolve("in.txt").toFile());
        FileObject out = vfsFile(tempDir.resolve("out.txt").toFile());
        writeText(in, "Z");

        AtomicInteger runs = new AtomicInteger();
        TaskDefinition def = new TaskDefinition() {
            @Override public String getId() { return "def"; }

            @Override
            public void configure(com.tyron.nanoj.api.tasks.TaskBuilder builder) {
                builder.inputs(in)
                        .outputs(out)
                        .option("mode", "copy");
            }

            @Override
            public void execute(com.tyron.nanoj.api.tasks.TaskExecutionContext context) throws Exception {
                runs.incrementAndGet();
                writeText(out, in.getText() + ":" + context.getOptions().get("mode", ""));
            }
        };

        Task t = tasks.register(def);
        var run1 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run1.getResult(t).getStatus());
        Assertions.assertEquals("Z:copy", out.getText());

        var run2 = tasks.run(t);
        Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, run2.getResult(t).getStatus());
        Assertions.assertEquals(1, runs.get());

        deleteRecursive(tempDir);
        deleteRecursive(cacheDir);
    }

    @Test
    public void testTaskDefinitionConfigureCanLookupUpstreamTaskById() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_def_lookup");
        Path cacheDir = Files.createTempDirectory("nanoj_tasks_def_lookup_cache");

        MockProject project = new MockProject(cacheDir.toFile());
        tasks = new TasksServiceImpl(project);

        FileObject in = vfsFile(tempDir.resolve("in.txt").toFile());
        FileObject mid = vfsFile(tempDir.resolve("mid.txt").toFile());
        FileObject out = vfsFile(tempDir.resolve("out.txt").toFile());
        writeText(in, "X");

        AtomicInteger javacRuns = new AtomicInteger();
        AtomicInteger dexRuns = new AtomicInteger();

        TaskDefinition javac = new TaskDefinition() {
            @Override public String getId() { return "javac"; }

            @Override
            public void configure(com.tyron.nanoj.api.tasks.TaskBuilder b) {
                b.inputs(in)
                        .outputs(mid);
            }

            @Override
            public void execute(com.tyron.nanoj.api.tasks.TaskExecutionContext ctx) throws Exception {
                javacRuns.incrementAndGet();
                writeText(mid, in.getText() + ":classes");
            }
        };

        TaskDefinition dex = new TaskDefinition() {
            @Override public String getId() { return "dex"; }

            @Override
            public void configure(com.tyron.nanoj.api.tasks.TaskBuilder b, TaskConfigurationContext c) {
                b.inputs(c.requireOutput("javac", 0)) // implies dependency
                        .outputs(out);
            }

            @Override
            public void execute(com.tyron.nanoj.api.tasks.TaskExecutionContext ctx) throws Exception {
                dexRuns.incrementAndGet();
                writeText(out, mid.getText() + ":dex");
            }
        };

        Task javacTask = tasks.register(javac);
        Task dexTask = tasks.register(dex);

        var run1 = tasks.run(dexTask);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run1.getResult(javacTask).getStatus());
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run1.getResult(dexTask).getStatus());
        Assertions.assertEquals(1, javacRuns.get());
        Assertions.assertEquals(1, dexRuns.get());
        Assertions.assertEquals("X:classes:dex", out.getText());

        var run2 = tasks.run(dexTask);
        Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, run2.getResult(javacTask).getStatus());
        Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, run2.getResult(dexTask).getStatus());
        Assertions.assertEquals(1, javacRuns.get());
        Assertions.assertEquals(1, dexRuns.get());

        deleteRecursive(tempDir);
        deleteRecursive(cacheDir);
    }

    @Test
    public void testContentHashFingerprintDetectsChangesEvenIfMetadataSame() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_hash");
        Path cacheDir = Files.createTempDirectory("nanoj_tasks_hash_cache");

        FileObject in = vfsFile(tempDir.resolve("in.txt").toFile());
        FileObject outMeta = vfsFile(tempDir.resolve("out-meta.txt").toFile());
        FileObject outHash = vfsFile(tempDir.resolve("out-hash.txt").toFile());

        // --- Run 1: write initial content, capture mtime
        writeText(in, "AAAA");
        FileTime t0 = Files.getLastModifiedTime(Path.of(in.getPath()));

        // Service instance #1
        MockProject project1 = new MockProject(cacheDir.toFile());
        TasksServiceImpl s1 = new TasksServiceImpl(project1);
        try {
            Task meta = s1.task("meta")
                    .fingerprintMode(TaskFingerprintMode.METADATA)
                    .inputs(in)
                    .outputs(outMeta)
                    .register(ctx -> writeText(outMeta, "ran"));

            Task hash = s1.task("hash")
                    .fingerprintMode(TaskFingerprintMode.CONTENT_HASH)
                    .inputs(in)
                    .outputs(outHash)
                    .register(ctx -> writeText(outHash, "ran"));

            Assertions.assertEquals(TaskResult.Status.EXECUTED, s1.run(meta).getResult(meta).getStatus());
            Assertions.assertEquals(TaskResult.Status.EXECUTED, s1.run(hash).getResult(hash).getStatus());
        } finally {
            s1.dispose();
        }

        // --- Modify content but preserve metadata
        writeText(in, "BBBB");
        Files.setLastModifiedTime(Path.of(in.getPath()), t0);

        // Service instance #2 (simulates restart)
        MockProject project2 = new MockProject(cacheDir.toFile());
        TasksServiceImpl s2 = new TasksServiceImpl(project2);
        try {
            Task meta2 = s2.task("meta")
                    .fingerprintMode(TaskFingerprintMode.METADATA)
                    .inputs(in)
                    .outputs(outMeta)
                    .register(ctx -> writeText(outMeta, "reran"));

            Task hash2 = s2.task("hash")
                    .fingerprintMode(TaskFingerprintMode.CONTENT_HASH)
                    .inputs(in)
                    .outputs(outHash)
                    .register(ctx -> writeText(outHash, "reran"));

            // Metadata fingerprint should consider it up-to-date (same size + restored mtime)
            Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, s2.run(meta2).getResult(meta2).getStatus());

            // Content hash fingerprint must re-run
            Assertions.assertEquals(TaskResult.Status.EXECUTED, s2.run(hash2).getResult(hash2).getStatus());
        } finally {
            s2.dispose();
        }

        deleteRecursive(tempDir);
        deleteRecursive(cacheDir);
    }

    @Test
    public void testOutputToInputChaining() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_chain");
        Path cacheDir = Files.createTempDirectory("nanoj_tasks_chain_cache");

        MockProject project = new MockProject(cacheDir.toFile());
        tasks = new TasksServiceImpl(project);

        FileObject in = vfsFile(tempDir.resolve("in.txt").toFile());
        FileObject mid = vfsFile(tempDir.resolve("mid.txt").toFile());
        FileObject out = vfsFile(tempDir.resolve("out.txt").toFile());

        writeText(in, "X");

        AtomicInteger aRuns = new AtomicInteger();
        AtomicInteger bRuns = new AtomicInteger();

        Task a = tasks.task("a")
                .inputs(in)
                .outputs(mid)
                .register(ctx -> {
                    aRuns.incrementAndGet();
                    writeText(mid, in.getText() + "1");
                });

        Task b = tasks.task("b")
                .inputs(a.output(0))
                .outputs(out)
                .register(ctx -> {
                    bRuns.incrementAndGet();
                    writeText(out, mid.getText() + "2");
                });

        var run1 = tasks.run(b);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run1.getResult(a).getStatus());
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run1.getResult(b).getStatus());
        Assertions.assertEquals(1, aRuns.get());
        Assertions.assertEquals(1, bRuns.get());
        Assertions.assertEquals("X12", out.getText());

        var run2 = tasks.run(b);
        Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, run2.getResult(a).getStatus());
        Assertions.assertEquals(TaskResult.Status.UP_TO_DATE, run2.getResult(b).getStatus());
        Assertions.assertEquals(1, aRuns.get());
        Assertions.assertEquals(1, bRuns.get());

        writeText(in, "Y");

        var run3 = tasks.run(b);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run3.getResult(a).getStatus());
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run3.getResult(b).getStatus());
        Assertions.assertEquals(2, aRuns.get());
        Assertions.assertEquals(2, bRuns.get());
        Assertions.assertEquals("Y12", out.getText());

        deleteRecursive(tempDir);
        deleteRecursive(cacheDir);
    }

    @Test
    public void testTwoIndependentTasksRunBeforeDependentTask() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_ab_c");
        Path cacheDir = Files.createTempDirectory("nanoj_tasks_ab_c_cache");

        MockProject project = new MockProject(cacheDir.toFile());
        tasks = new TasksServiceImpl(project);

        FileObject aOut = vfsFile(tempDir.resolve("a.txt").toFile());
        FileObject bOut = vfsFile(tempDir.resolve("b.txt").toFile());
        FileObject cOut = vfsFile(tempDir.resolve("c.txt").toFile());

        StringBuilder order = new StringBuilder();

        Task a = tasks.task("A")
                .outputs(aOut)
                .register(ctx -> {
                    order.append('A');
                    writeText(aOut, "a");
                    System.out.println("RUNNING TASK A");
                });

        Task b = tasks.task("B")
                .outputs(bOut)
                .register(ctx -> {
                    order.append('B');
                    writeText(bOut, "b");
                    System.out.println("RUNNING TASK B");
                });

        Task c = tasks.task("C")
                .inputs(a.output(0), b.output(0)) // implies deps on A and B
                .outputs(cOut)
                .register(ctx -> {
                    // Both outputs must exist when C runs
                    Assertions.assertTrue(aOut.exists());
                    Assertions.assertTrue(bOut.exists());
                    order.append('C');
                    writeText(cOut, aOut.getText() + bOut.getText());

                    System.out.println("RUNNING TASK C");
                });

        var run = tasks.run(c);
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run.getResult(a).getStatus());
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run.getResult(b).getStatus());
        Assertions.assertEquals(TaskResult.Status.EXECUTED, run.getResult(c).getStatus());

        // Must have C after both A and B (A/B order relative to each other is unspecified)
        String o = order.toString();
        Assertions.assertEquals(3, o.length());
        Assertions.assertEquals('C', o.charAt(2));

        deleteRecursive(tempDir);
        deleteRecursive(cacheDir);
    }

    @Test
    public void testCrossProjectTaskDependenciesRunFirst() throws Exception {
        Path tempDir = Files.createTempDirectory("nanoj_tasks_xproj");

        Path cacheA = Files.createTempDirectory("nanoj_tasks_xproj_cacheA");
        Path cacheB = Files.createTempDirectory("nanoj_tasks_xproj_cacheB");
        Path cacheC = Files.createTempDirectory("nanoj_tasks_xproj_cacheC");

        MockProject projectA = new MockProject(cacheA.toFile());
        projectA.getConfiguration().setProperty("nanoj.id", "projA");
        MockProject projectB = new MockProject(cacheB.toFile());
        projectB.getConfiguration().setProperty("nanoj.id", "projB");
        MockProject projectC = new MockProject(cacheC.toFile());
        projectC.getConfiguration().setProperty("nanoj.id", "projC");

        TasksServiceImpl tasksA = new TasksServiceImpl(projectA);
        TasksServiceImpl tasksB = new TasksServiceImpl(projectB);
        TasksServiceImpl tasksC = new TasksServiceImpl(projectC);

        // Make TasksService.getInstance(projectX) work for external dependencies.
        ProjectServiceManager.registerInstance(projectA, TasksService.class, tasksA);
        ProjectServiceManager.registerInstance(projectB, TasksService.class, tasksB);
        ProjectServiceManager.registerInstance(projectC, TasksService.class, tasksC);

        // Register projects in the global registry for id-based lookup.
        ProjectRegistry.getInstance().register("projA", projectA);
        ProjectRegistry.getInstance().register("projB", projectB);
        ProjectRegistry.getInstance().register("projC", projectC);

        try {
            FileObject aOut = vfsFile(tempDir.resolve("a.txt").toFile());
            FileObject bOut = vfsFile(tempDir.resolve("b.txt").toFile());
            FileObject cOut = vfsFile(tempDir.resolve("c.txt").toFile());

            StringBuilder order = new StringBuilder();

            Task a = tasksA.task("A")
                    .outputs(aOut)
                    .register(ctx -> {
                        order.append('A');
                        writeText(aOut, "a");
                    });

            Task b = tasksB.task("B")
                    .outputs(bOut)
                    .register(ctx -> {
                        order.append('B');
                        writeText(bOut, "b");
                    });

                Task c = tasksC.task("C")
                    .inputs(tasksC.output("projA", "A", 0), tasksC.output("projB", "B", 0))
                    .outputs(cOut)
                    .register(ctx -> {
                        Assertions.assertTrue(aOut.exists());
                        Assertions.assertTrue(bOut.exists());
                        order.append('C');
                        writeText(cOut, aOut.getText() + bOut.getText());
                    });

            var run = tasksC.run(c);
            Assertions.assertEquals(TaskResult.Status.EXECUTED, run.getResult(c).getStatus());

            String o = order.toString();
            Assertions.assertEquals(3, o.length());
            Assertions.assertEquals('C', o.charAt(2));
            Assertions.assertEquals("ab", cOut.getText());
        } finally {
            tasksA.dispose();
            tasksB.dispose();
            tasksC.dispose();

            try {
                ProjectRegistry.getInstance().unregister("projA");
                ProjectRegistry.getInstance().unregister("projB");
                ProjectRegistry.getInstance().unregister("projC");
            } catch (Throwable ignored) {
            }

            ProjectServiceManager.disposeProject(projectA);
            ProjectServiceManager.disposeProject(projectB);
            ProjectServiceManager.disposeProject(projectC);

            projectA.dispose();
            projectB.dispose();
            projectC.dispose();

            deleteRecursive(tempDir);
            deleteRecursive(cacheA);
            deleteRecursive(cacheB);
            deleteRecursive(cacheC);
        }
    }

    private static FileObject vfsFile(File file) {
        return VirtualFileManager.getInstance().find(file);
    }

    private static void writeText(FileObject file, String text) throws IOException {
        try (OutputStream out = file.getOutputStream()) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walk(p)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
    }
}
