# Testing in Nanoj

This document explains Nanoj’s testing approach and the `:test-framework` module.

Nanoj tests are written with **JUnit 5** and focus on validating core IDE infrastructure (VFS/VFM, indexing, editor/document, completion, compiler integration).

## Goals of the test framework

Nanoj’s runtime is an IDE-like system with global registries and project-scoped services. Tests need to:

- create isolated “projects” cheaply
- control the virtual file layer deterministically
- simulate file events that would normally come from the UI/runtime
- avoid the overhead and flakiness of real filesystem watchers

The `:test-framework` module exists to make these tests:

- fast
- deterministic
- easy to write

## Where tests live

- Core infrastructure tests: `core/src/test/java/...`
- Java language/plugin tests: `lang-java/src/test/java/...`
- Shared test utilities: `test-framework/src/main/java/...`

## The `:test-framework` module

Main classes:

- `BaseIdeTest`
- `BaseEditorTest`
- `BaseCompletionTest`
- `BaseJavaIndexingTest`
- `TestProjectBuilder`
- `Stopwatch` (simple timing utility)

### `BaseIdeTest`: the foundation

File: `test-framework/src/main/java/com/tyron/nanoj/testFramework/BaseIdeTest.java`

What it does per test:

- Creates a JUnit `@TempDir` directory and uses it for:
  - a project root directory
  - a cache directory (used by components like `IndexManager`)

- Resets global file infrastructure:
  - calls `VirtualFileSystem.getInstance().clear()` so global scheme registrations/listeners don’t leak between tests

- Installs a deterministic in-memory filesystem:
  - registers `MockFileSystem` which hijacks the `file:` scheme

- Creates a mutable project model:
  - `MockProject` (from `core/.../test/MockProject.java`)

- Disposes project services after each test:
  - `ProjectServiceManager.disposeProject(project)`
  - `project.dispose()`

Helpers:

- `file(path, content)` / `dir(path)` create in-memory files/folders in the mock FS
- the helpers also emit VFS events via `VirtualFileSystem.fireFileCreated(...)`

Why it matters:

- most IDE subsystems rely on VFS events (indexing, file/document commits, etc.)
- tests can simulate those events without a real filesystem

### `MockProject`, `MockFileSystem`, `MockFileObject`

These live under `core/src/main/java/com/tyron/nanoj/core/test/`.

- `MockProject` implements `Project` and is fully mutable:
  - source roots, classpath, build dir, properties, etc.

- `MockFileSystem` implements `FileSystem`:
  - stores `FileObject`s in a map and resolves by path
  - returns scheme `file` so it replaces `LocalFileSystem` in tests

- `MockFileObject` implements `FileObject`:
  - minimal, in-memory content
  - sufficient for indexing/editor tests

## Editor tests

Use:

- `BaseEditorTest` (`test-framework/.../BaseEditorTest.java`)

It calls:

- `EditorCore.register(project)`

This binds editor services (`FileDocumentManager`, `EditorManager`) via `ProjectServiceManager`.

Example tests:

- `core/src/test/java/.../editor/EditorInfrastructureTest.java`

## Completion tests

Use:

- `BaseCompletionTest` (`test-framework/.../BaseCompletionTest.java`)

It does additional setup:

- registers completion infrastructure via `CompletionCore.register(project)`
- fires project open lifecycle: `ProjectLifecycle.fireProjectOpened(project)`
- sets `IndexManager.DUMB_THRESHOLD_MS_KEY` to a large value so unit tests run in “smart mode” by default

Example tests:

- `core/src/test/java/.../completion/AutoPopupCompletionTest.java`
- `lang-java/src/test/java/.../completion/*`

If you need to test dumb mode behavior explicitly, override the property or use the dumb-mode tests as references.

## Indexing tests

There are two common patterns:

### 1) Pure indexing unit tests

Some tests instantiate `IndexManager` directly with a mock `Project` and register simple `IndexDefinition` implementations.

Example:

- `core/src/test/java/.../indexing/IndexManagerTest.java`

### 2) Java binary indexing tests

Use:

- `BaseJavaIndexingTest` (`test-framework/.../BaseJavaIndexingTest.java`)

It provides helpers to compile Java source in-memory and emit `.class` bytes for indexers:

- `compile(className, source)`
- `compileMulti(mainClassName, source)` (captures inner classes)
- `createClassFile(binaryName, bytes)` registers a `.class` file under `/libs/...` and fires a created event

Example tests:

- `lang-java/src/test/java/.../indexing/JavaBinaryStubIndexerTest.java`
- `lang-java/src/test/java/.../indexing/ShortClassNameIndexTest.java`

## Benchmarks vs tests

Nanoj contains at least one opt-in benchmark-like test:

- `lang-java/src/test/java/.../indexing/JrtIndexBenchmarkTest.java`

It is annotated:

- `@Tag("benchmark")`

And is intended to be run explicitly (it prints timings and may take longer than normal unit tests).

Recommendation:

- keep unit tests deterministic and fast
- keep benchmarks opt-in (tagged) and avoid making them required for CI-by-default

## Writing new tests (recommended flow)

1. Pick the closest base class:
   - IDE infra only → `BaseIdeTest`
   - editor/document → `BaseEditorTest`
   - completion → `BaseCompletionTest`
   - Java `.class` indexers → `BaseJavaIndexingTest`

2. Use the helpers:
   - create files with `file("src/main/java/...", "...")`
   - configure roots with `configureJavaProject()` or `TestProjectBuilder`

3. Trigger behavior via events:
   - VFS create/change events are important for indexing/pipelines

4. Clean up:
   - base classes dispose services/projects already; avoid global singletons in tests

## Running tests

From the repo root:

```bash
./gradlew test
```

Run a single test class:

```bash
./gradlew :core:test --tests com.tyron.nanoj.core.vfs.VirtualFileSystemTest
```

Run Java language tests only:

```bash
./gradlew :lang-java:test
```

Run benchmark-tagged tests (example):

```bash
./gradlew :lang-java:test --tests com.tyron.nanoj.lang.java.indexing.JrtIndexBenchmarkTest -Dnanoj.bench.run=true
```

## Common pitfalls

- Forgetting to reset global registries:
  - use `BaseIdeTest` (it clears `VirtualFileSystem` per test)

- Forgetting to dispose services:
  - `BaseIdeTest.tearDown()` calls `ProjectServiceManager.disposeProject(project)`

- Tests depending on timing:
  - prefer `IndexManager.flush()` for deterministic completion of indexing tasks

- Accidentally testing real filesystem behavior:
  - tests should prefer `MockFileSystem` + explicit event emission
