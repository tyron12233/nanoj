# Virtual File System (VFS)

This module provides a minimal **Virtual File System** abstraction around storage backends (disk, in-memory, archives, etc.).

## Key Types

- `com.tyron.nanoj.api.vfs.FileObject`
  - A handle to a file or folder.
  - Read/write happens through `getInputStream()` / `getOutputStream()`.

- `com.tyron.nanoj.api.vfs.FileSystem`
  - A backend implementation (e.g. local disk, in-memory).
  - Identified by a URI scheme (e.g. `file`).

- `com.tyron.nanoj.core.vfs.VirtualFileSystem`
  - Global registry + dispatcher.
  - Maps URI schemes to `FileSystem`s.
  - Propagates file events to **global listeners**.

## Event Model (Important)

### The contract

1. A `FileSystem` implementation is responsible for firing **its own** events via its registered listeners:
   - `FileSystem.addFileChangeListener(...)`
   - `FileSystem.removeFileChangeListener(...)`

2. `VirtualFileSystem` installs an internal **bridge listener** on each registered `FileSystem`.
   - When the FS emits an event, the VFS forwards it to VFS-global listeners added via:
     - `VirtualFileSystem.addGlobalListener(...)`

### Why this matters

- File systems should **not** call `VirtualFileSystem.fire*()` directly.
- Doing so would double-fire events once the bridge is installed.

### Rename events

- Use `com.tyron.nanoj.api.vfs.FileRenameEvent` for rename operations.
  - `getOldFile()` returns the old path
  - `getFile()` returns the new file

`LocalFileObject.rename(...)` currently fires:
- a `fileRenamed` event (rich rename)
- and also legacy `fileDeleted` + `fileCreated` events (for compatibility with older listeners)

## Typical Usage

### Resolving files

```java
FileObject fo = VirtualFileSystem.getInstance().find(new java.io.File("/path/to/file.txt"));
FileObject fo2 = VirtualFileSystem.getInstance().find("/path/to/file.txt");
FileObject fo3 = VirtualFileSystem.getInstance().find("file:///path/to/file.txt");
```

### Listening for changes

```java
VirtualFileSystem.getInstance().addGlobalListener(new FileChangeListener() {
  @Override public void fileCreated(FileEvent event) {}
  @Override public void fileDeleted(FileEvent event) {}
  @Override public void fileChanged(FileEvent event) {}
  @Override public void fileRenamed(FileEvent event) {}
});
```

## Testing

- In tests, you can register a custom `FileSystem` (e.g. `MockFileSystem`) under the same scheme.
- `VirtualFileSystem.clear()` resets state and re-registers the default `LocalFileSystem`.
- Tests that want a different backend typically do:
  1. `VirtualFileSystem.getInstance().clear()`
  2. `VirtualFileSystem.getInstance().register(mockFs)`

## Notes

- `LocalFileObject` instances are immutable views of a path. After rename, the original `FileObject` still points to the old path.
- `FileObject.delete()` on directories typically fails if the directory is not empty; use `com.tyron.nanoj.core.vfs.FileUtil.deleteRecursively(...)`.
