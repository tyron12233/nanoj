# Editor API in Nanoj

This document explains Nanoj’s editor API:

- the core abstractions (`Document`, `Editor`, `EditorManager`, `FileDocumentManager`)
- how editing is wired into the rest of the IDE (VFS, indexing, completion)
- what is extensible (language integration, completion, highlighting)
- design constraints (mobile performance, low allocation)

> Design note
>
> The general approach (document/editor separation, a file-to-document manager, language-provided completion/highlighting, and the concept of restricting features while indexing) is **inspired by IntelliJ IDEA**. Nanoj’s API is intentionally smaller and tuned for mobile constraints.

## Why have an editor API?

An IDE needs a stable internal model for text editing that features can build on:

- completion providers need the current text + caret position
- indexers/builders need access to file contents (including unsaved changes)
- syntax highlighting needs incremental updates without blocking the UI

Nanoj’s editor API provides those hooks while keeping the core model platform-agnostic (Android UI vs desktop UI).

## Key concepts

### `Document`

File: `api/src/main/java/com/tyron/nanoj/api/editor/Document.java`

A `Document` is the in-memory text model:

- `getText()`, `getTextLength()`
- editing operations: `replace(start, end, text)`, `insertString(offset, text)`, `deleteString(start, end)`
- range reads: `getText(start, length)`

#### Document change events

Nanoj supports change listeners via:

- `DocumentListener` + `DocumentEvent`
- `ObservableDocument` (optional extension of `Document` for implementations that publish events)

Core uses this to track modified state.

### `Editor`

File: `api/src/main/java/com/tyron/nanoj/api/editor/Editor.java`

An `Editor` is an abstract view/controller that edits a `Document`.

- `Editor.getDocument()`
- `Editor.getCaretModel()` (simple caret abstraction)
- `scrollToCaret()` (UI can implement this)

Nanoj’s core `Editor` interface is intentionally minimal so it can be wrapped by different UI toolkits.

### `EditorManager`

File: `api/src/main/java/com/tyron/nanoj/api/editor/EditorManager.java`

Manages the lifecycle of editor instances:

- `openEditor(FileObject file)`
  - creates or reuses the document and returns an editor
- `createEditor(Document document)`
- `releaseEditor(Editor editor)`
- `getEditors(Document document)` / `getAllEditors()`

Core implementation:

- `core/.../editor/EditorManagerImpl.java`

Notes:

- Multiple editors may exist for the same document.
- `EditorManagerImpl` is a non-UI registry; a UI layer can replace it.

### `FileDocumentManager`

File: `api/src/main/java/com/tyron/nanoj/api/editor/FileDocumentManager.java`

Bridges persistent files and in-memory documents:

- caches `Document` per `FileObject`
- tracks “modified” state
- commits documents to disk on demand
- provides an in-memory file view for unsaved content

Core implementation:

- `core/.../editor/FileDocumentManagerImpl.java`

Important behavior:

- Documents are edited in memory; they are only persisted when committed.
- On commit, `FileDocumentManagerImpl` fires a VFS change event:
  - `VirtualFileSystem.getInstance().fireFileChanged(file)`
  so indexing and other subsystems can react.

### `SyntaxHighlighter`

File: `api/.../editor/SyntaxHighlighter.java`

Highlighting is asynchronous:

- `CompletableFuture<List<TokenSpan>> highlight(String content)`

This is designed so UI can avoid blocking while highlighting runs.

## How it is used in the IDE

### Open a file

Typical flow:

1. UI resolves file via VFM/VFS: `FileObject`
2. UI asks `EditorManager.openEditor(file)`
3. `FileDocumentManager` loads/caches a `Document`
4. editor instance edits the `Document`

### Track unsaved changes

- `FileDocumentManagerImpl` attaches a `DocumentListener` to each document.
- Any document mutation marks it modified.

### Save (commit) a document

- `commitDocument(document)` writes bytes to `FileObject.getOutputStream()`
- then fires `VirtualFileSystem.fireFileChanged(file)`

That event is the bridge to re-indexing and other invalidation logic.

## Extensibility: language integration

Nanoj’s editing features are extended primarily through `LanguageSupport` extensions.

In `core/.../editor/EditorSession.java`:

- it queries `ProjectServiceManager.getExtensions(project, LanguageSupport.class)`
- picks a language handler that `canHandle(file)`
- then uses it to create:
  - a `SyntaxHighlighter`
  - a `CompletionProvider`

This mirrors the “language plugin” architecture used by desktop IDEs.

### Completion and dumb mode

`EditorSession.onCompletionRequest(...)` checks dumb mode:

- If the project is dumb and the completion provider is not `DumbAware`, results are suppressed.

This is the editor-facing behavior that keeps the UI responsive while indexing is in progress.

## Project wiring (service bindings)

Editor infrastructure is registered via:

- `core/.../editor/EditorCore.register(project)`

This binds interfaces to implementations using `ProjectServiceManager`:

- `FileDocumentManager` → `FileDocumentManagerImpl`
- `EditorManager` → `EditorManagerImpl`

It also ensures dumb-mode infrastructure is present (`DumbCore.register(project)`).

## Design constraints (why it looks “small”)

Nanoj targets mobile constraints:

- lower memory budgets
- GC sensitivity
- UI responsiveness

So the editor API:

- keeps the core interfaces minimal
- pushes heavy work async (`SyntaxHighlighter`)
- relies on simple service + extension wiring rather than a large framework

## Relationship to IntelliJ

Nanoj’s editor/document split and “file ↔ document ↔ editor” flow is inspired by IntelliJ’s model:

- documents are the canonical in-memory text
- editors are views/controllers over documents
- file/document managers coordinate persistence
- language plugins provide completion/highlighting
- dumb mode restricts expensive features during indexing

Nanoj differs in that it is intentionally lightweight and designed for mobile performance.
