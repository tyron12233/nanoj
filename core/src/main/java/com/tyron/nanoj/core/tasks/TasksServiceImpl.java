package com.tyron.nanoj.core.tasks;

import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.api.concurrent.TaskPriority;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.tasks.*;
import com.tyron.nanoj.api.vfs.*;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core implementation of the project-scoped Tasks API.
 */
public final class TasksServiceImpl implements TasksService, Disposable {

    private static final int STATE_VERSION = 3;

    private final Project project;
    private final DB db;
    private final HTreeMap<String, byte[]> stateByTaskId;

    private final Map<String, DefaultTask> tasksById = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<TaskExecutionListener> listeners = new CopyOnWriteArrayList<>();

    // path -> taskIds
    private final Map<String, Set<String>> pathToTaskIds = new ConcurrentHashMap<>();

    // taskIds marked dirty by VFS
    private final Set<String> dirtyTaskIds = ConcurrentHashMap.newKeySet();

    private final FileChangeListener vfsListener = new FileChangeListener() {
        @Override
        public void fileCreated(FileEvent event) {
            markDirtyForPath(event.getFile().getPath());
        }

        @Override
        public void fileDeleted(FileEvent event) {
            markDirtyForPath(event.getFile().getPath());
        }

        @Override
        public void fileChanged(FileEvent event) {
            markDirtyForPath(event.getFile().getPath());
        }

        @Override
        public void fileRenamed(FileEvent event) {
            // VirtualFileSystem also emits delete/create for rename; treat this as a hint.
            markDirtyForPath(event.getFile().getPath());
        }
    };

    public TasksServiceImpl(Project project) {
        this.project = Objects.requireNonNull(project, "project");

        File dbFile = new File(project.getCacheDir(), "nanoj_tasks.db");
        this.db = DBMaker.fileDB(dbFile)
                .fileMmapEnable()
                .fileMmapPreclearDisable()
                .cleanerHackEnable()
                .transactionEnable()
                .closeOnJvmShutdown()
                .make();

        this.stateByTaskId = db.hashMap("sys_tasks_state", Serializer.STRING, Serializer.BYTE_ARRAY).createOrOpen();

        VirtualFileManager.getInstance().addGlobalListener(vfsListener);
    }

    @Override
    public Project getProject() {
        return project;
    }

    @Override
    public TaskBuilder task(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is blank");
        return new Builder(id.trim());
    }

    @Override
    public FileObject file(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is blank");
        }

        String p = path.trim().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);

        // Absolute path or URI-like -> delegate to VFS
        if (p.startsWith("/") || p.contains("://") || p.startsWith("jar:")) {
            return VirtualFileManager.getInstance().find(p);
        }

        // Project-relative path -> resolve via root directory
        FileObject current = project.getRootDirectory();
        if (p.isEmpty()) return current;

        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                FileObject parent = current.getParent();
                if (parent != null) current = parent;
                continue;
            }
            current = current.getChild(seg);
        }
        return current;
    }

    @Override
    public TaskRun run(Task task) {
        Objects.requireNonNull(task, "task");
        Map<String, TaskResult> results = new LinkedHashMap<>();
        executeTask(task.getId(), results, new HashSet<>());
        return () -> Collections.unmodifiableMap(results);
    }

    @Override
    public CompletableFuture<TaskRun> runAsync(Task task) {
        Objects.requireNonNull(task, "task");

        TaskScheduler scheduler;
        try {
            scheduler = project.getService(TaskScheduler.class);
        } catch (Throwable t) {
            scheduler = null;
        }

        if (scheduler == null) {
            return CompletableFuture.supplyAsync(() -> run(task));
        }

        return scheduler.submitLatest("tasks", task.getId(), TaskPriority.BACKGROUND, ctx -> run(task));
    }

    @Override
    public Task getTask(String id) {
        if (id == null) return null;
        return tasksById.get(id);
    }

    @Override
    public void addListener(TaskExecutionListener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(TaskExecutionListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    @Override
    public void dispose() {
        VirtualFileManager.getInstance().removeGlobalListener(vfsListener);
        db.close();
    }

    private void markDirtyForPath(String path) {
        if (path == null) return;
        Set<String> taskIds = pathToTaskIds.get(path);
        if (taskIds == null || taskIds.isEmpty()) return;
        dirtyTaskIds.addAll(taskIds);
    }

    private void executeTask(String taskId, Map<String, TaskResult> results, Set<String> visiting) {
        TaskResult existing = results.get(taskId);
        if (existing != null) return;

        DefaultTask task = tasksById.get(taskId);
        if (task == null) {
            throw new IllegalStateException("Unknown task: " + taskId);
        }

        if (!visiting.add(taskId)) {
            throw new IllegalStateException("Task cycle detected at: " + taskId);
        }

        for (Task dep : task.dependencies) {
            TaskResult depResult;

            if (dep instanceof ProjectTask projectTask && projectTask.getProject() != null && projectTask.getProject() != project) {
                depResult = runExternalDependency(projectTask);
            } else {
                executeTask(dep.getId(), results, visiting);
                depResult = results.get(dep.getId());
            }

            if (depResult != null && depResult.getStatus() == TaskResult.Status.FAILED) {
                // Do not attach the original error as a cause; it would duplicate long messages
                // (e.g. compiler output) for every downstream task.
                TaskResult r = TaskResult.failed(taskId, depResult.getStartedAtMs(), depResult.getFinishedAtMs(),
                    new IllegalStateException("Dependency failed: " + dep.getId()));
                results.put(taskId, r);
                notifyFinished(task, r);
                visiting.remove(taskId);
                return;
            }
        }

        notifyStarted(task);
        long started = System.currentTimeMillis();
        try {
            boolean upToDate = isUpToDate(task);
            if (upToDate) {
                long finished = System.currentTimeMillis();
                TaskResult r = TaskResult.upToDate(taskId, started, finished);
                results.put(taskId, r);
                dirtyTaskIds.remove(taskId);
                visiting.remove(taskId);
                notifyFinished(task, r);
                return;
            }

            DefaultTaskExecutionContext ctx = new DefaultTaskExecutionContext(project, task);
            task.action.execute(ctx);

            persistState(task, ctx.getOptions());
            dirtyTaskIds.remove(taskId);

            long finished = System.currentTimeMillis();
            TaskResult r = TaskResult.executed(taskId, started, finished);
            results.put(taskId, r);
            notifyFinished(task, r);
        } catch (Throwable t) {
            long finished = System.currentTimeMillis();
            TaskResult r = TaskResult.failed(taskId, started, finished, t);
            results.put(taskId, r);
            notifyFinished(task, r);
        } finally {
            visiting.remove(taskId);
        }
    }

    private void notifyStarted(Task task) {
        if (listeners.isEmpty()) return;
        for (TaskExecutionListener l : listeners) {
            try {
                l.onTaskStarted(task);
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyFinished(Task task, TaskResult result) {
        if (listeners.isEmpty()) return;
        for (TaskExecutionListener l : listeners) {
            try {
                l.onTaskFinished(task, result);
            } catch (Throwable ignored) {
            }
        }
    }

    private static TaskResult runExternalDependency(ProjectTask dep) {
        Project depProject = dep.getProject();
        long started = System.currentTimeMillis();
        try {
            TasksService depService = TasksService.getInstance(depProject);
            TaskRun run = depService.run(dep);
            TaskResult result = run.getResult(dep);
            if (result == null) {
                long finished = System.currentTimeMillis();
                return TaskResult.failed(dep.getId(), started, finished,
                        new IllegalStateException("External dependency did not report result: " + dep.getId()));
            }
            return result;
        } catch (Throwable t) {
            long finished = System.currentTimeMillis();
            return TaskResult.failed(dep.getId(), started, finished, t);
        }
    }

    private boolean isUpToDate(DefaultTask task) {
        if (!task.cacheable) return false;
        if (dirtyTaskIds.contains(task.id)) return false;

        byte[] stored = stateByTaskId.get(task.id);
        if (stored == null) return false;

        TaskState state;
        try {
            state = decodeState(stored);
        } catch (IOException e) {
            return false;
        }

        long optionsHash = hashOptions(task.options);
        if (state.optionsHash != optionsHash) return false;

        List<SnapshotEntry> currentInputs = snapshotAll(task.fingerprintMode, task.getAllInputEntries());
        if (!snapshotsEqual(state.inputs, currentInputs)) return false;

        List<SnapshotEntry> currentOutputs = snapshotAll(task.fingerprintMode, task.getAllOutputEntries());
        if (!allOutputsExist(currentOutputs)) return false;
        return snapshotsEqual(state.outputs, currentOutputs);
    }

    private void persistState(DefaultTask task, TaskOptions optionsAtExecution) throws IOException {
        if (!task.cacheable) return;

        long optionsHash = hashOptions(optionsAtExecution);
        List<SnapshotEntry> inputs = snapshotAll(task.fingerprintMode, task.getAllInputEntries());
        List<SnapshotEntry> outputs = snapshotAll(task.fingerprintMode, task.getAllOutputEntries());

        TaskState state = new TaskState(optionsHash, inputs, outputs);
        stateByTaskId.put(task.id, encodeState(state));
        db.commit();
    }

    private static boolean allOutputsExist(List<SnapshotEntry> outputs) {
        for (SnapshotEntry s : outputs) {
            if (!s.exists) return false;
        }
        return true;
    }

    private static List<SnapshotEntry> snapshotAll(TaskFingerprintMode mode, List<InputEntry> entries) {
        List<SnapshotEntry> out = new ArrayList<>();
        for (InputEntry e : entries) {
            if (e == null) continue;
            VfsSnapshot snap = VfsSnapshots.snapshot(e.file, e.mode, true);
            for (VfsSnapshot.Entry se : snap.getEntries()) {
                out.add(SnapshotEntry.from(mode, se));
            }
        }
        out.sort(Comparator.comparing(s -> s.path));
        return out;
    }

    private static boolean snapshotsEqual(List<SnapshotEntry> a, List<SnapshotEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    private static long hashOptions(TaskOptions options) {
        if (options == null) return 0L;
        Map<String, String> map = options.asMap();
        if (map.isEmpty()) return 0L;
        List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        long h = 1125899906842597L;
        for (Map.Entry<String, String> e : entries) {
            h = 31L * h + Objects.hashCode(e.getKey());
            h = 31L * h + Objects.hashCode(e.getValue());
        }
        return h;
    }

    private static byte[] encodeState(TaskState state) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(STATE_VERSION);
            out.writeLong(state.optionsHash);

            writeSnapshots(out, state.inputs);
            writeSnapshots(out, state.outputs);
        }
        return baos.toByteArray();
    }

    private static TaskState decodeState(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != 1 && version != 2 && version != STATE_VERSION) throw new IOException("Unsupported version: " + version);

            long optionsHash = in.readLong();
            List<SnapshotEntry> inputs = readSnapshots(in, version);
            List<SnapshotEntry> outputs = readSnapshots(in, version);
            return new TaskState(optionsHash, inputs, outputs);
        }
    }

    private static void writeSnapshots(DataOutputStream out, List<SnapshotEntry> snaps) throws IOException {
        out.writeInt(snaps.size());
        for (SnapshotEntry s : snaps) {
            out.writeUTF(s.path);
            out.writeBoolean(s.exists);
            out.writeBoolean(s.folder);
            out.writeLong(s.lastModified);
            out.writeLong(s.length);

            boolean hasHash = s.contentHashHex != null;
            out.writeBoolean(hasHash);
            if (hasHash) {
                out.writeUTF(s.contentHashHex);
            }
        }
    }

    private static List<SnapshotEntry> readSnapshots(DataInputStream in, int version) throws IOException {
        int n = in.readInt();
        List<SnapshotEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String path = in.readUTF();
            boolean exists = in.readBoolean();
            boolean folder = (version >= 2) && in.readBoolean();
            if (version == 1) {
                folder = false;
            }
            long lastModified = in.readLong();
            long length = in.readLong();

            String hash = null;
            if (version >= 3) {
                boolean hasHash = in.readBoolean();
                if (hasHash) {
                    hash = in.readUTF();
                }
            }

            out.add(new SnapshotEntry(path, exists, folder, lastModified, length, hash));
        }
        out.sort(Comparator.comparing(s -> s.path));
        return out;
    }

    private void replaceTask(DefaultTask task) {
        DefaultTask previous = tasksById.put(task.id, task);
        if (previous != null) {
            unregisterTaskPaths(previous);
        }
        registerTaskPaths(task);
    }

    private void unregisterTaskPaths(DefaultTask task) {
        for (String path : task.getAllReferencedPaths()) {
            Set<String> set = pathToTaskIds.get(path);
            if (set == null) continue;
            set.remove(task.id);
            if (set.isEmpty()) pathToTaskIds.remove(path);
        }
    }

    private void registerTaskPaths(DefaultTask task) {
        for (String path : task.getAllReferencedPaths()) {
            pathToTaskIds.computeIfAbsent(path, p -> ConcurrentHashMap.newKeySet()).add(task.id);
        }
    }

    private static final class TaskState {
        final long optionsHash;
        final List<SnapshotEntry> inputs;
        final List<SnapshotEntry> outputs;

        TaskState(long optionsHash, List<SnapshotEntry> inputs, List<SnapshotEntry> outputs) {
            this.optionsHash = optionsHash;
            this.inputs = inputs;
            this.outputs = outputs;
        }
    }

    private static final class SnapshotEntry {
        final String path;
        final boolean exists;
        final boolean folder;
        final long lastModified;
        final long length;
        final String contentHashHex;

        SnapshotEntry(String path, boolean exists, boolean folder, long lastModified, long length, String contentHashHex) {
            this.path = path;
            this.exists = exists;
            this.folder = folder;
            this.lastModified = lastModified;
            this.length = length;
            this.contentHashHex = contentHashHex;
        }

        static SnapshotEntry from(TaskFingerprintMode mode, VfsSnapshot.Entry e) {
            String hash = null;
            if (mode == TaskFingerprintMode.CONTENT_HASH && e.exists() && !e.isFolder()) {
                try {
                    FileObject fo = VirtualFileManager.getInstance().find(e.getPath());
                    hash = sha256Hex(fo);
                } catch (Throwable ignored) {
                    hash = null;
                }
            }

            return new SnapshotEntry(
                    e.getPath(),
                    e.exists(),
                    e.isFolder(),
                    e.getLastModified(),
                    e.getLength(),
                    hash
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
                if (!(o instanceof SnapshotEntry that)) return false;
            return exists == that.exists
                    && folder == that.folder
                    && lastModified == that.lastModified
                    && length == that.length
                    && Objects.equals(path, that.path)
                    && Objects.equals(contentHashHex, that.contentHashHex);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, exists, folder, lastModified, length, contentHashHex);
        }
    }

    private static String sha256Hex(FileObject fo) throws IOException {
        if (fo == null || !fo.exists() || fo.isFolder()) return null;
        try (InputStream in = fo.getInputStream()) {
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }

            byte[] buf = new byte[32 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                md.update(buf, 0, r);
            }

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
    }

    private static final class InputEntry {
        final FileObject file;
        final VfsSnapshot.Mode mode;

        InputEntry(FileObject file, VfsSnapshot.Mode mode) {
            this.file = file;
            this.mode = mode;
        }
    }

    private static final class DefaultTask implements ProjectTask {
        final Project project;
        final String id;
        final boolean cacheable;
        final List<FileObject> directInputs;
        final List<FileObject> treeInputs;
        final List<TaskOutput> outputInputs;
        final List<TaskOutput> outputs;
        final List<FileObject> treeOutputs;
        final List<Task> dependencies;
        final TaskOptions options;
        final TaskFingerprintMode fingerprintMode;
        final TaskAction action;

        DefaultTask(
                Project project,
                String id,
                boolean cacheable,
                List<FileObject> directInputs,
                List<FileObject> treeInputs,
                List<TaskOutput> outputInputs,
                List<TaskOutput> outputs,
            List<FileObject> treeOutputs,
                List<Task> dependencies,
                TaskOptions options,
                TaskFingerprintMode fingerprintMode,
                TaskAction action
        ) {
            this.project = Objects.requireNonNull(project, "project");
            this.id = id;
            this.cacheable = cacheable;
            this.directInputs = directInputs;
            this.treeInputs = treeInputs;
            this.outputInputs = outputInputs;
            this.outputs = outputs;
            this.treeOutputs = treeOutputs;
            this.dependencies = dependencies;
            this.options = options;
            this.fingerprintMode = (fingerprintMode != null) ? fingerprintMode : TaskFingerprintMode.METADATA;
            this.action = action;
        }

        @Override
        public Project getProject() {
            return project;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean isCacheable() {
            return cacheable;
        }

        @Override
        public List<TaskOutput> getOutputs() {
            return outputs;
        }

        List<InputEntry> getAllInputEntries() {
            List<InputEntry> all = new ArrayList<>();
            for (FileObject f : directInputs) {
                if (f != null) all.add(new InputEntry(f, VfsSnapshot.Mode.SELF));
            }
            for (FileObject f : treeInputs) {
                if (f != null) all.add(new InputEntry(f, VfsSnapshot.Mode.TREE));
            }
            for (TaskOutput o : outputInputs) {
                if (o != null && o.getFile() != null) all.add(new InputEntry(o.getFile(), VfsSnapshot.Mode.SELF));
            }
            return all;
        }

        List<InputEntry> getAllOutputEntries() {
            List<InputEntry> all = new ArrayList<>(outputs.size() + treeOutputs.size());
            for (TaskOutput o : outputs) {
                if (o != null && o.getFile() != null) all.add(new InputEntry(o.getFile(), VfsSnapshot.Mode.SELF));
            }
            for (FileObject f : treeOutputs) {
                if (f != null) all.add(new InputEntry(f, VfsSnapshot.Mode.TREE));
            }
            return all;
        }

        Set<String> getAllReferencedPaths() {
            Set<String> out = new HashSet<>();
            for (FileObject f : directInputs) if (f != null) out.add(f.getPath());
            for (FileObject f : treeInputs) if (f != null) out.add(f.getPath());
            for (TaskOutput o : outputInputs) if (o != null && o.getFile() != null) out.add(o.getFile().getPath());
            for (TaskOutput o : outputs) if (o != null && o.getFile() != null) out.add(o.getFile().getPath());
            for (FileObject f : treeOutputs) if (f != null) out.add(f.getPath());
            return out;
        }
    }

    private static final class DefaultTaskOutput implements TaskOutput {
        private final Task task;
        private final FileObject file;

        DefaultTaskOutput(Task task, FileObject file) {
            this.task = task;
            this.file = file;
        }

        @Override
        public Task getTask() {
            return task;
        }

        @Override
        public FileObject getFile() {
            return file;
        }
    }

    private static final class DefaultTaskExecutionContext implements TaskExecutionContext {
        private final Project project;
        private final Task task;
        private final TaskOptions options;

        DefaultTaskExecutionContext(Project project, DefaultTask task) {
            this.project = project;
            this.task = task;
            this.options = task.options;
        }

        @Override
        public Project getProject() {
            return project;
        }

        @Override
        public Task getTask() {
            return task;
        }

        @Override
        public TaskOptions getOptions() {
            return options;
        }
    }

    private final class Builder implements TaskBuilder {
        private final String id;

        private boolean cacheable = true;
        private TaskFingerprintMode fingerprintMode = TaskFingerprintMode.METADATA;
        private final List<FileObject> inputs = new ArrayList<>();
        private final List<FileObject> inputTrees = new ArrayList<>();
        private final List<TaskOutput> outputInputs = new ArrayList<>();
        private final List<FileObject> outputs = new ArrayList<>();
        private final List<FileObject> outputTrees = new ArrayList<>();
        private final List<Task> dependencies = new ArrayList<>();
        private final TaskOptionsBuilder options = new TaskOptionsBuilder();

        Builder(String id) {
            this.id = id;
        }

        @Override
        public TaskBuilder fingerprintMode(TaskFingerprintMode mode) {
            this.fingerprintMode = (mode != null) ? mode : TaskFingerprintMode.METADATA;
            return this;
        }

        @Override
        public TaskBuilder dependsOn(Task... tasks) {
            if (tasks != null) {
                for (Task t : tasks) {
                    if (t != null) dependencies.add(t);
                }
            }
            return this;
        }

        @Override
        public TaskBuilder inputs(FileObject... files) {
            if (files != null) {
                for (FileObject f : files) {
                    if (f != null) inputs.add(f);
                }
            }
            return this;
        }

        @Override
        public TaskBuilder inputTrees(FileObject... roots) {
            if (roots != null) {
                for (FileObject f : roots) {
                    if (f != null) inputTrees.add(f);
                }
            }
            return this;
        }

        @Override
        public TaskBuilder inputs(TaskOutput... outputs) {
            if (outputs != null) {
                for (TaskOutput o : outputs) {
                    if (o == null) continue;
                    outputInputs.add(o);
                    Task dep = o.getTask();
                    if (dep != null) dependencies.add(dep);
                }
            }
            return this;
        }

        @Override
        public TaskBuilder outputs(FileObject... files) {
            if (files != null) {
                for (FileObject f : files) {
                    if (f != null) outputs.add(f);
                }
            }
            return this;
        }

        @Override
        public TaskBuilder outputTrees(FileObject... roots) {
            if (roots != null) {
                for (FileObject f : roots) {
                    if (f != null) outputTrees.add(f);
                }
            }
            return this;
        }

        @Override
        public TaskBuilder option(String key, String value) {
            options.put(key, value);
            return this;
        }

        @Override
        public TaskBuilder options(java.util.function.Consumer<TaskOptionsBuilder> optionsConfigurer) {
            if (optionsConfigurer != null) {
                optionsConfigurer.accept(options);
            }
            return this;
        }

        @Override
        public TaskBuilder cacheable(boolean cacheable) {
            this.cacheable = cacheable;
            return this;
        }

        @Override
        public Task register(TaskAction action) {
            Objects.requireNonNull(action, "action");

            List<TaskOutput> out = new ArrayList<>(outputs.size());
            DefaultTask task = new DefaultTask(
                    project,
                    id,
                    cacheable,
                    List.copyOf(inputs),
                    List.copyOf(inputTrees),
                    List.copyOf(outputInputs),
                    out,
                    List.copyOf(outputTrees),
                    List.copyOf(dependencies),
                    options.build(),
                    fingerprintMode,
                    action
            );

            for (FileObject f : outputs) {
                out.add(new DefaultTaskOutput(task, f));
            }

            replaceTask(task);
            return task;
        }
    }
}
