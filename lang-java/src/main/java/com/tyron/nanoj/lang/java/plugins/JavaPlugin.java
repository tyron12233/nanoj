package com.tyron.nanoj.lang.java.plugins;

import com.tyron.nanoj.api.plugins.ProjectPlugin;
import com.tyron.nanoj.api.plugins.ProjectPluginContext;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.tasks.TaskFingerprintMode;
import com.tyron.nanoj.api.tasks.Task;
import com.tyron.nanoj.api.tasks.TasksService;
import com.tyron.nanoj.api.vfs.FileObject;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * "java" plugin: compiles Java sources, processes resources, builds a jar, and can run a main class.
 *
 * <p>Skips Maven dependency resolution; uses {@link Project#getClassPath()} and {@link Project#getBootClassPath()}.
 */
public final class JavaPlugin implements ProjectPlugin {

    public static final String ID = "java";

    // Task ids
    public static final String TASK_PROCESS_RESOURCES = "processResources";
    public static final String TASK_COMPILE_JAVA = "compileJava";
    public static final String TASK_JAR = "jar";
    public static final String TASK_BUILD = "build";
    public static final String TASK_RUN = "run";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void apply(ProjectPluginContext ctx) {
        Objects.requireNonNull(ctx, "ctx");

        Project project = ctx.getProject();
        TasksService tasks = ctx.getTasks();

        FileObject buildDir = project.getBuildDirectory();
        FileObject classesDir = child(buildDir, "classes");
        FileObject resourcesDir = child(buildDir, "resources");
        FileObject libsDir = child(buildDir, "libs");
        FileObject runDir = child(buildDir, "run");
        FileObject runLog = child(runDir, "console.txt");

        String jarName = ctx.getOptions().get("jarName", project.getName() + ".jar");
        if (!jarName.endsWith(".jar")) jarName = jarName + ".jar";
        FileObject jarFile = child(libsDir, jarName);

        int javaVersion = ctx.getOptions().getInt("javaVersion", project.getConfiguration().getJavaVersion());
        String mainClass = ctx.getOptions().get("mainClass", "");

        Task processResources = tasks.task(TASK_PROCESS_RESOURCES)
                .cacheable(true)
                .inputTrees(project.getResourceRoots().toArray(FileObject[]::new))
                .outputTrees(resourcesDir)
                .register(exec -> {
                    ensureDirectory(resourcesDir);
                    copyResourceRoots(project.getResourceRoots(), resourcesDir);
                });

        Task compileJava = tasks.task(TASK_COMPILE_JAVA)
                .cacheable(true)
                .dependsOn(processResources)
                .fingerprintMode(TaskFingerprintMode.CONTENT_HASH)
                .inputTrees(project.getSourceRoots().toArray(FileObject[]::new))
                .outputTrees(classesDir)
                .option("javaVersion", Integer.toString(javaVersion))
                .register(exec -> {
                    ensureDirectory(classesDir);
                    compileProjectJava(project, classesDir, javaVersion);
                });

        Task jar = tasks.task(TASK_JAR)
                .cacheable(true)
                .fingerprintMode(TaskFingerprintMode.CONTENT_HASH)
                .dependsOn(processResources, compileJava)
                .inputTrees(classesDir, resourcesDir)
                .outputs(jarFile)
                .option("jarName", jarName)
                .register(exec -> {
                    ensureDirectory(libsDir);
                    buildJar(project, jarFile, classesDir, resourcesDir, mainClass);
                });

        // Convenience aggregate task.
        tasks.task(TASK_BUILD)
                .cacheable(false)
                .dependsOn(jar)
                .register(exec -> {
                    // no-op
                });

        // Optional run task.
        tasks.task(TASK_RUN)
                .cacheable(false)
                .dependsOn(jar)
                .option("mainClass", mainClass)
                .outputs(runLog)
                .register(exec -> {
                    String mc = mainClass != null ? mainClass.trim() : "";
                    if (mc.isEmpty()) {
                        throw new IllegalStateException("No mainClass configured for java plugin (set nanoj.plugin.java.option.mainClass)");
                    }
                    ensureDirectory(runDir);
                    runJar(project, jarFile, mc, Path.of(runLog.getPath()));
                });
    }

    private static FileObject child(FileObject dir, String name) {
        FileObject c = dir.getChild(name);
        return c != null ? c : dir;
    }

    private static void ensureDirectory(FileObject dir) throws IOException {
        Path p = Path.of(dir.getPath());
        Files.createDirectories(p);
        dir.refresh();
    }

    private static void copyResourceRoots(List<FileObject> roots, FileObject outDir) throws IOException {
        Path out = Path.of(outDir.getPath());
        for (FileObject root : roots) {
            if (root == null || !root.exists() || !root.isFolder()) continue;
            Path rp = Path.of(root.getPath());
            if (!Files.exists(rp)) continue;

            Files.walk(rp)
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(src -> {
                        try {
                            Path rel = rp.relativize(src);
                            Path dst = out.resolve(rel);
                            Files.createDirectories(dst.getParent());
                            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        outDir.refresh();
    }

    private static void compileProjectJava(Project project, FileObject classesDir, int javaVersion) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available (ToolProvider.getSystemJavaCompiler() returned null)");
        }

        List<File> sources = new ArrayList<>();
        for (FileObject root : project.getSourceRoots()) {
            if (root == null || !root.exists() || !root.isFolder()) continue;
            Path rp = Path.of(root.getPath());
            if (!Files.exists(rp)) continue;
            Files.walk(rp)
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> sources.add(p.toFile()));
        }

        if (sources.isEmpty()) {
            return;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(classesDir.getPath());

        String cp = joinExisting(project.getClassPath());
        if (!cp.isEmpty()) {
            options.add("-classpath");
            options.add(cp);
        }

        String bcp = joinExisting(project.getBootClassPath());
        if (!bcp.isEmpty()) {
            // Best-effort; supported on standard javac.
            options.add("-bootclasspath");
            options.add(bcp);
        }

        if (javaVersion > 0) {
            if (javaVersion >= 9) {
                options.add("--release");
                options.add(Integer.toString(javaVersion));
            } else {
                options.add("-source");
                options.add(Integer.toString(javaVersion));
                options.add("-target");
                options.add(Integer.toString(javaVersion));
            }
        }

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, options, null, units);
            Boolean ok = task.call();
            if (ok == null || !ok) {
                throw new IllegalStateException(renderDiagnostics(diagnostics));
            }
        }

        classesDir.refresh();
    }

    private static String joinExisting(List<FileObject> files) {
        if (files == null || files.isEmpty()) return "";
        String sep = File.pathSeparator;
        StringBuilder sb = new StringBuilder();
        for (FileObject fo : files) {
            if (fo == null) continue;
            String p = fo.getPath();
            if (p == null || p.isBlank()) continue;
            if (!new File(p).exists()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String renderDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        sb.append("javac failed\n");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            sb.append(d.getKind())
                    .append(": ")
                    .append(d.getMessage(null));
            if (d.getSource() != null) {
                sb.append(" (source=").append(d.getSource().toUri()).append(")");
            }
            if (d.getLineNumber() > 0) {
                sb.append(" line=").append(d.getLineNumber());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void buildJar(Project project, FileObject jarFile, FileObject classesDir, FileObject resourcesDir, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null && !mainClass.isBlank()) {
            attrs.put(Attributes.Name.MAIN_CLASS, mainClass.trim());
        }

        Path jarPath = Path.of(jarFile.getPath());
        Files.createDirectories(jarPath.getParent());

        try (OutputStream fout = new FileOutputStream(jarPath.toFile());
             JarOutputStream jout = new JarOutputStream(fout, manifest)) {

            Set<String> seen = new HashSet<>();
            addDirToJar(jout, Path.of(classesDir.getPath()), seen);
            addDirToJar(jout, Path.of(resourcesDir.getPath()), seen);
        }

        jarFile.refresh();

        // Best-effort: refresh jar FS caches.
        try {
            // Touching the jar via JarFile validates it and helps some fs layers.
            try (JarFile jf = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> e = jf.entries();
                while (e.hasMoreElements()) {
                    e.nextElement();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addDirToJar(JarOutputStream jout, Path dir, Set<String> seen) throws IOException {
        if (dir == null || !Files.exists(dir)) return;

        List<Path> files = new ArrayList<>();
        Files.walk(dir)
                .filter(Files::isRegularFile)
                .forEach(files::add);

        files.sort(Comparator.comparing(Path::toString));

        for (Path f : files) {
            String name = dir.relativize(f).toString().replace('\\', '/');
            if (name.isEmpty()) continue;
            if (!seen.add(name)) continue;

            JarEntry entry = new JarEntry(name);
            entry.setTime(0L); // stable output
            jout.putNextEntry(entry);
            Files.copy(f, jout);
            jout.closeEntry();
        }
    }

    private static void runJar(Project project, FileObject jarFile, String mainClass, Path outLogFile) throws IOException, InterruptedException {
        String javaHome = System.getProperty("java.home");
        File javaExe = new File(javaHome, "bin/java");
        String javaCmd = javaExe.exists() ? javaExe.getAbsolutePath() : "java";

        List<String> cmd = new ArrayList<>();
        cmd.add(javaCmd);
        cmd.add("-cp");
        cmd.add(jarFile.getPath());
        cmd.add(mainClass);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(project.getRootDirectory().getPath()));
        pb.redirectErrorStream(true);

        Process p = pb.start();

        // Capture output to file so UIs can display it.
        Files.createDirectories(outLogFile.getParent());
        try (InputStream in = p.getInputStream();
             OutputStream out = new FileOutputStream(outLogFile.toFile())) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }

        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("java exited with code " + code);
        }
    }
}
