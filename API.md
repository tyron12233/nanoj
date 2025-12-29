# NanoJ API

This repository is split into modules; the public surface area lives in `:api`.

The `:api` module exists so `:core` and language plugins can depend on stable-ish interfaces while implementations evolve.

## What’s in `:api`

- **Project model + services**
  - `Project` (configuration, roots/classpath wiring)
  - `ProjectServiceManager` extension/service model (see [`PROJECT_SERVICES.md`](PROJECT_SERVICES.md))

- **Virtual file layer (VFS)**
  - `FileObject` abstraction (works for `file:`, `jar:`, `jrt:`)
  - change events used by indexing and editor infrastructure
  - more details: [`VIRTUAL_FILE_MANAGER.md`](VIRTUAL_FILE_MANAGER.md)

- **Editor model**
  - `Document`, `Editor`, caret model, and persistence hooks
  - more details: [`EDITOR_API.md`](EDITOR_API.md)

- **Completion API**
  - `LookupElement` (presentation + insert handler)
  - `CompletionParameters` (text + caret offset + file)
  - `LookupElementWeigher` extension point to influence ordering
    - examples in `:core`: exact-prefix weighting, element-kind weighting

- **Diagnostics API**
  - language-agnostic diagnostics interfaces
  - implementations (e.g. Java/javac) live in language modules

## Extension points you’ll likely use

- Completion ordering: implement `LookupElementWeigher` and register it for the project.
- Language diagnostics: implement the diagnostics provider for your language.
- Indexing: implement index definitions in `:core`-compatible style and register them for your project.

## Where to look next

- [`PROJECT_SERVICES.md`](PROJECT_SERVICES.md) (services/extensions)
- [`INDEXING.md`](INDEXING.md) and [`SHARED_INDEXES.md`](SHARED_INDEXES.md) (indexing + shared indexes)
- [`TESTING.md`](TESTING.md) (how to write deterministic tests using `:test-framework`)
