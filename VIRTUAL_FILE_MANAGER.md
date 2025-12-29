# Virtual File Manager (VFM)

Nanoj uses a Virtual File Manager (historically named “Virtual File System” / VFS in the code) to provide a **single, uniform file API** across multiple storage backends.

> Naming
>
> In code, the central registry is `VirtualFileSystem` (`core/.../vfs/VirtualFileSystem.java`).
> For clarity in documentation and architecture discussions, Nanoj also provides the alias `VirtualFileManager` (`core/.../vfs/VirtualFileManager.java`).
> The term “manager” is used because this component primarily **routes** operations and **manages** multiple file systems and their events.

## Why it exists

A mobile IDE needs to handle files from different sources:

- local disk (`file:`)
- jar/zip archives (`jar:`)
- JDK modules (`jrt:`)
- (potentially) in-memory file systems, remote content, SAF/document providers, etc.

If every subsystem (indexing, editor buffers, compiler integration, project model) directly used `java.io.File`, you quickly run into:

- inconsistent path/URI handling
- special-case logic everywhere (jar entries, `jrt:` modules)
- platform-specific storage constraints (Android storage policies)
- inability to substitute or test with mock file systems

The VFM centralizes these concerns behind the `FileObject` abstraction.

## What it does (and what it does not)

### It *does*

- Maintain a registry of `FileSystem` implementations by scheme (e.g. `file`, `jar`, `jrt`).
- Convert paths/URIs into `FileObject` instances via `find(...)`.
- Bridge filesystem-local file events to global listeners.
- Allow replacing/unregistering file systems by scheme.

### It *does not*

- Implement a full POSIX-like filesystem.
- Provide file watching itself for every backend; each `FileSystem` may have its own event model.
- Enforce caching policies globally (individual `FileSystem` / `FileObject` implementations may cache).

## Key types

From `:api`:

- `FileObject`
  - normalized handle to a file-like entity (disk file, jar entry, jrt entry)
  - provides common operations (children, contents, metadata)

- `FileSystem`
  - backend for a scheme (e.g. `file:`, `jar:`, `jrt:`)
  - resolves a URI to a `FileObject`
  - can emit file change events

From `:core`:

- `VirtualFileSystem`
  - the central registry/router and event bridge

- Built-in file systems:
  - `LocalFileSystem` (`file:`)
  - `JarFileSystem` (`jar:`)
  - `JrtFileSystem` (`jrt:`)

## How routing works

`VirtualFileSystem.find(...)` determines which backend to use:

1. if you pass a `File`, it becomes a `file:` URI
2. if you pass a `URI`, the URI scheme is used (`file`, `jar`, `jrt`)
3. the corresponding `FileSystem` is looked up in the registry
4. the `FileSystem` resolves the URI to a `FileObject`

If no filesystem is registered for a scheme, `VirtualFileSystem` throws.

## Event model (global listeners)

Nanoj components often want to react to file changes (indexing, editor buffers, etc.).

`VirtualFileSystem` supports global listeners:

- `addGlobalListener(...)`
- `removeGlobalListener(...)`

When a filesystem is registered, `VirtualFileSystem` attaches a **bridge listener** to it.
That bridge re-emits events (`created`, `deleted`, `changed`, `renamed`) to all global listeners.

This avoids every subsystem needing to subscribe to every filesystem independently.

## Why not use an existing VFS library?

There are mature VFS libraries (e.g. Apache Commons VFS), so why have a custom one?

Nanoj’s constraints are different:

1. **Mobile performance + memory constraints**
   - Nanoj needs very low allocation overhead and predictable I/O patterns.
   - Many general-purpose VFS libraries are optimized for flexibility, not minimal allocations.

2. **IDE-specific requirements**
   - Tight integration with indexing, caching, and incremental change propagation.
   - Strong preference for a tiny API surface (`FileObject`, `FileSystem`) that the rest of the IDE can depend on.

3. **Platform isolation (Android)**
   - Android storage rules and providers change over time.
   - A thin abstraction layer lets Nanoj adapt backends without rewriting the IDE core.

4. **URI schemes that matter to Java tooling**
   - `jar:` and `jrt:` are core to Java compilation and indexing.
   - Nanoj can specialize these backends heavily (caching, interning, traversal strategies).

5. **Testability**
   - Nanoj’s test framework can register mock file systems and fire synthetic events.

In short: Nanoj’s VFM is intentionally small and purpose-built for IDE workloads.

## Common usage

Resolve a path:

```java
FileObject fo = VirtualFileManager.getInstance().find("/sdcard/project/src/Main.java");
```

Resolve a jar root:

```java
FileObject jarRoot = VirtualFileManager.getInstance().find(URI.create("jar:file:/path/to/lib.jar!/"));
```

Listen for changes:

```java
VirtualFileManager.getInstance().addGlobalListener(listener);
```

## Extension points

To add a new backend:

1. implement `com.tyron.nanoj.api.vfs.FileSystem`
2. return a stable `getScheme()` (e.g. `http`, `mem`, `saf`)
3. register it:

```java
VirtualFileManager.getInstance().register(myFs);
```

If your backend can produce change events, emit them through its listener mechanism so the VFM bridge can propagate them.

## Relationship to IntelliJ

The high-level idea (a Virtual File System abstraction used by indexing/editor/PSI-like layers) is **inspired by IntelliJ**.

Nanoj differs by design:

- fewer layers
- a smaller API
- backends optimized for mobile constraints
- direct integration with Nanoj’s indexing pipeline and performance strategies
