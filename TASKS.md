# Tasks (Project-scoped incremental execution)

NanoJ provides a small, fluent Tasks API for declaring file-based work with dependencies and incremental caching.

## Core ideas

- Tasks are **project-scoped services**: `TasksService.getInstance(project)`
- A task declares:
  - `inputs(...)` (files it reads)
  - `inputTrees(...)` (folders it reads, recursively)
  - `outputs(...)` (files it produces)
  - `dependsOn(...)` (explicit dependencies)
- You can chain outputs into inputs via `inputs(otherTask.output(0))`.
- Tasks are **incremental by default**:
  - if inputs + outputs + options match the last successful run, the task is skipped as `UP_TO_DATE`.

## Path helpers

For convenience, `TasksService` can resolve project-relative paths to VFS `FileObject`s:

```java
FileObject src = tasks.dir("src/main/java");
FileObject manifest = tasks.file("AndroidManifest.xml");
FileObject[] libs = tasks.files("libs/a.jar", "libs/b.jar");
```

Absolute paths and URIs are also supported (delegated to the VFS).

## Fingerprinting (metadata vs content hash)

By default, up-to-date checks use file metadata:

- exists
- lastModified
- length

If you need stronger change detection (e.g. tools that preserve timestamps, or same-size edits), enable content hashing:

```java
Task t = tasks.task("compile")
  .fingerprintMode(TaskFingerprintMode.CONTENT_HASH)
  .inputs(input)
  .outputs(output)
  .register(ctx -> { /* ... */ });
```

This computes SHA-256 for file entries during snapshotting.

## Example

```java
TasksService tasks = TasksService.getInstance(project);

Task gen = tasks.task("gen")
    .inputs(project.getRootDirectory().getChild("in.txt"))
    .outputs(project.getRootDirectory().getChild("mid.txt"))
    .register(ctx -> {
        // write mid.txt
    });

Task compile = tasks.task("compile")
    .inputs(gen.output(0)) // implies dependency
    .outputs(project.getRootDirectory().getChild("out.txt"))
  .option("debug", "true")
    .register(ctx -> {
        // write out.txt
    });

TaskRun run = tasks.run(compile);
System.out.println(run.getResult(compile).getStatus());
```

## Class-based tasks (complex tasks)

For tasks that deserve their own class (e.g. Java compiler), implement `TaskDefinition`:

```java
final class JavaCompileTask implements TaskDefinition {
  @Override public String getId() { return "javac"; }

  @Override public void configure(TaskBuilder b) {
    b.inputTrees(project.getRootDirectory().getChild("src"))
     .outputs(project.getBuildDirectory().getChild("classes"))
     .options(o -> o.putBoolean("debug", true));
  }

  @Override public void execute(TaskExecutionContext ctx) {
    boolean debug = ctx.getOptions().getBoolean("debug", false);
    // compile...
  }
}

Task javac = tasks.register(new JavaCompileTask());
```

### Wiring outputs by task id

If you want to reference upstream outputs inside `configure(...)` without constructor injection, override the context-aware overload:

```java
final class DexTask implements TaskDefinition {
  @Override public String getId() { return "dex"; }

  @Override
  public void configure(TaskBuilder b, TaskConfigurationContext c) {
    b.inputs(c.requireOutput("javac", 0)) // implies dependency on "javac"
     .outputs(project.getBuildDirectory().getChild("classes.dex"));
  }

  @Override
  public void execute(TaskExecutionContext ctx) {
    // dex...
  }
}

tasks.register(new JavaCompileTask());
Task dex = tasks.register(new DexTask());
tasks.run(dex);
```

## Wiring from project configuration

Tasks are just a project-scoped graph, so the normal pattern is:

- load/build your project model (source roots, libraries, toolchain, etc.)
- register tasks using that model

In NanoJ the cleanest place to do this is a `ProjectLifecycleListener` (e.g. on `projectOpened(...)`), similar to how indexing is wired.

### Project config file (root)

NanoJ supports a root config file `nanoj.yaml` (or `nanoj.yml`) which is loaded on project open:

```yaml
id: my-app

subprojects:
  - id: core
    path: core
  - id: desktop
    path: desktop

properties:
  javaVersion: 17

plugins:
  # Plugins are applied in order and can register tasks for the project type.
  # Items can be a string id, or an object with options.
  - id: java-library
    options:
      javaVersion: 17
  - application
```

This is flattened into `Project.getConfiguration()` keys:

- `nanoj.id = my-app`
- `nanoj.subprojects = core,desktop`
- `nanoj.subproject.core.path = core`
- `nanoj.properties.javaVersion = 17`
- `nanoj.plugins = java-library,application`
- `nanoj.plugin.java-library.option.javaVersion = 17`

### Plugins (Gradle-like)

Different project types (CLI app, Java library, Java application, Android app, etc.) typically need different task sets.
NanoJ supports a simple plugin mechanism so those task sets can be applied declaratively.

- Declare plugins in `nanoj.yaml` under `plugins:`.
- Register available plugins via the application-scoped `ProjectPluginRegistry`.
- On project open, `ProjectConfigLifecycleListener` applies configured plugins in order.

Plugin authors implement `ProjectPlugin`:

```java
public final class JavaLibraryPlugin implements ProjectPlugin {
  @Override public String getId() { return "java-library"; }

  @Override
  public void apply(ProjectPluginContext ctx) {
    // Read plugin options
    int javaVersion = ctx.getOptions().getInt("javaVersion", ctx.getProject().getConfiguration().getJavaVersion());

    // Register tasks
    TasksService tasks = ctx.getTasks();
    tasks.task("compileJava")
      .option("javaVersion", Integer.toString(javaVersion))
      .inputTrees(tasks.dir("src/main/java"))
      .outputs(tasks.dir("build/classes"))
      .register(exec -> {
        // compile...
      });
  }
}
```

Registering the plugin (application-scoped):

```java
ProjectPluginRegistry.getInstance().register("java-library", project -> new JavaLibraryPlugin());
```

### Built-in: `java` plugin

When the `java` plugin is applied, it registers these tasks:

- `processResources` → copies `Project.getResourceRoots()` into `build/resources`
- `compileJava` → compiles `Project.getSourceRoots()` into `build/classes`
- `jar` → packages `build/classes` + `build/resources` into `build/libs/<jarName>`
- `build` → depends on `jar`
- `run` → depends on `jar` and runs `mainClass` (non-cacheable)

Config keys (via `nanoj.yaml`):

```yaml
plugins:
  - id: java
    options:
      jarName: app.jar
      javaVersion: 17
      mainClass: com.example.Main
```

Inside `TaskDefinition.configure(...)` you can also read the project model via the configuration context:

```java
final class JavaCompileTask implements TaskDefinition {
  @Override public String getId() { return "javac"; }

  @Override
  public void configure(TaskBuilder b, TaskConfigurationContext c) {
    // Example: wire all source roots as inputs
    b.inputTrees(c.getProject().getSourceRoots().toArray(FileObject[]::new));

    // Example: include a config value in the fingerprint so changes force re-run
    String javaVersion = Integer.toString(c.getProject().getConfiguration().getJavaVersion());
    b.option("javaVersion", javaVersion);

    b.outputs(c.getProject().getBuildDirectory().getChild("classes"));
  }

  @Override public void execute(TaskExecutionContext ctx) {
    // compile...
  }
}
```

If the project configuration changes at runtime, re-register the affected tasks (same id replaces the prior task).

## Cross-project dependencies

Tasks can depend on outputs produced by tasks from other projects. If task `C` declares an input produced by another project's task, that upstream task is executed first (via that project's `TasksService`).

```java
TasksService tasksA = TasksService.getInstance(projectA);
Task a = tasksA.task("A")
  .outputs(tasksA.file("build/a.txt"))
  .register(ctx -> { /* write build/a.txt */ });

TasksService tasksB = TasksService.getInstance(projectB);
Task b = tasksB.task("B")
  .outputs(tasksB.file("build/b.txt"))
  .register(ctx -> { /* write build/b.txt */ });

TasksService tasksC = TasksService.getInstance(projectC);
Task c = tasksC.task("C")
  // If you don't have Task/Project references at config time, use ids:
  .inputs(tasksC.output("projectA", "A", 0), tasksC.output("projectB", "B", 0))
  .outputs(tasksC.file("build/c.txt"))
  .register(ctx -> { /* read a+b outputs and write c */ });

tasksC.run(c);
```

Note: this requires each project to have `TasksService` bound/registered (e.g. via `EditorCore.register(project)`).

## Implementation notes

- Implementation lives in `:core` as `TasksServiceImpl`.
- Cached state is stored under `project.getCacheDir()` in `nanoj_tasks.db`.
- The service subscribes to VFS global events and uses them as a fast “dirty” signal; up-to-date checks still verify VFS snapshots.
