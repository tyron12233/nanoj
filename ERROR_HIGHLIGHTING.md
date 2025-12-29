# Error Highlighting (Diagnostics)

This document describes Nanoj’s **error highlighting** API.

Error highlighting is **not syntax highlighting**:
- **Syntax highlighting** colors tokens (keywords, strings, comments) using a lexer/parser.
- **Error highlighting** produces **diagnostics** (errors, warnings, info) with **ranges** in the current text snapshot, suitable for squiggles, gutter markers, and tooltips.

---

## Goals

- Provide a stable, language-agnostic API to compute diagnostics for a file + text snapshot.
- Support unsaved/in-memory edits.
- Let language implementations plug in without coupling core/editor to language internals.
- Make desktop integration possible (RSyntaxTextArea notices).

Non-goals:
- This API does not define UI rendering (squiggle styles, colors, tool windows).
- This API does not implement quick-fixes.

---

## API overview

### Key types

Located in `api/src/main/java/com/tyron/nanoj/api/diagnostics/`:

- `DiagnosticSeverity` — `ERROR`, `WARNING`, `INFO`.
- `Diagnostic` — a single diagnostic with:
  - `startOffset`, `endOffset` in the provided text snapshot (0-based, range is `[startOffset, endOffset)`).
  - `message` (human readable)
  - optional `code` and `source`
- `DiagnosticsProvider` — language-specific implementation for a file.
- `ErrorHighlightingService` — project-scoped facade used by editors/clients.

### Ranges

Diagnostics are expressed as character offsets into the exact `text` passed to `getDiagnostics(file, text)`.

- Offsets are **0-based**.
- The range is `[startOffset, endOffset)` (end exclusive).
- Providers should clamp ranges to the snapshot length.

---

## Core service

`ErrorHighlightingService` is implemented by `core/src/main/java/com/tyron/nanoj/core/diagnostics/ErrorHighlightingServiceImpl.java`.

It:
- Gets an in-memory view of `FileObject` via `FileDocumentManagerImpl` so language services that read from the file see unsaved edits.
- Finds a `LanguageSupport` that can handle the file.
- Asks it for a `DiagnosticsProvider` and delegates.

Registration:
- `EditorCore.register(project)` binds `ErrorHighlightingService` → `ErrorHighlightingServiceImpl`.

---

## Language integration

### `LanguageSupport`

`LanguageSupport` now has an optional hook:

- `createDiagnosticsProvider(Project project, FileObject file)` (default returns `null`)

Languages that support diagnostics should override and return a provider.

---

## Java implementation

Java diagnostics are implemented using the existing parsing infrastructure:

- `lang-java/src/main/java/com/tyron/nanoj/lang/java/source/JavaSource.java`
  - New `runDiagnosticsTask(text, action)` entry point.
  - Uses `CompilationInfo.Phase.RESOLVED` (so type errors are included) but runs at LOW priority.

- `lang-java/src/main/java/com/tyron/nanoj/lang/java/diagnostics/JavaDiagnosticsProvider.java`
  - Reads `CompilationInfo.getDiagnostics()` (javac diagnostics).
  - Converts to Nanoj `Diagnostic` objects and clamps ranges.

- `lang-java/src/main/java/com/tyron/nanoj/lang/java/JavaLanguageSupport.java`
  - Wires `createDiagnosticsProvider(...)` to `JavaDiagnosticsProvider`.

---

## Desktop (RSyntaxTextArea) integration

RSyntaxTextArea supports error/warning highlighting via its **parser API** (`Parser`, `ParseResult`, `ParserNotice`).

Nanoj provides a bridge parser:

- `desktop/src/main/java/com/tyron/nanoj/desktop/diagnostics/NanojDiagnosticsParser.java`

It:
- Calls `ErrorHighlightingService.getDiagnostics(file, text)`.
- Converts each `Diagnostic` into a `DefaultParserNotice` with offset/length.

Wiring example (already done in `desktop/src/main/java/com/tyron/nanoj/desktop/DesktopApp.java`):

```java
editor.addParser(new NanojDiagnosticsParser(project, fileObject, editor));
```

Notes:
- RSyntaxTextArea runs parsers on its own schedule; keep providers reasonably fast.
- Diagnostics should be computed against the same text snapshot RSyntaxTextArea is parsing.

---

## Usage examples

### Compute diagnostics (core/editor)

```java
ErrorHighlightingService svc = ProjectServiceManager.getService(project, ErrorHighlightingService.class);
List<Diagnostic> diags = svc.getDiagnostics(file, currentText);
```

### Typical UI rendering

- For each `Diagnostic`:
  - underline `[startOffset, endOffset)`
  - show tooltip with `message`
  - display gutter marker based on `severity`

---

## Testing

- `lang-java/src/test/java/com/tyron/nanoj/lang/java/diagnostics/JavaDiagnosticsTest.java` asserts broken code produces at least one `ERROR` diagnostic.
