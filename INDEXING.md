# Indexing in Nanoj

This document explains Nanoj’s indexing system: **why it exists**, **how it stores and queries data**, how it interacts with **dumb mode**, and how to extend it.

> Design note
>
> The overall model (persistent indexes, incremental updates, a “dumb mode” while indexes are being built, and query APIs used by completion/resolve) is **inspired by IntelliJ IDEA**. Nanoj’s implementation is intentionally smaller/simpler, but the workflow and concepts are similar.

## Why Nanoj has indexes

Parsing or scanning the entire project/classpath every time you need a feature (completion, navigation, symbol search, Javac file manager lookups) is too slow.

Indexes provide:

- **Fast lookups**: “find classes starting with `Lis`”, “list types in package `java.util`”, etc.
- **Incremental updates**: update only the affected file(s) after edits.
- **Persistent cache**: retain results across IDE restarts.

## High-level architecture

Core pieces:

- `IndexManager` (`core/.../indexing/IndexManager.java`)
  - owns the persistent database
  - schedules background indexing work
  - exposes query methods (`processValues`, `processPrefix`, `search`, `searchPrefix`)
  - coordinates dumb mode while indexing is running

- `IndexDefinition<K, V>` (`core/.../indexing/spi/IndexDefinition.java`)
  - per-index plugin contract
  - decides which files it supports
  - maps a file to key/value entries
  - defines value serialization/deserialization

- `MapDBIndexWrapper` (`core/.../indexing/MapDBIndexWrapper.java`)
  - stores two maps per index:
    - **inverted**: `key -> [packet...]` (supports exact and prefix queries)
    - **forward**: `fileId -> [key...]` (supports invalidation when a file changes)

### Storage format (inverted packets)

Nanoj stores each entry as a single byte array “packet”:

- first 4 bytes: the `fileId` (big-endian int)
- remaining bytes: the serialized value payload

This allows:

- retrieving the `fileId` quickly when enumerating a key’s values
- decoding the `V` value on demand

## Index lifecycle

### Registration

Indexes are registered at runtime via:

- `IndexManager.register(IndexDefinition)`

Registration ensures a MapDB structure exists for that index ID.

### When indexing runs

Indexing work is scheduled when:

- the VFS notifies changes and `IndexManager.updateFile(file)` is called
- tests/tools call `IndexManager.updateFileAsync(file)` or `updateFilesAsync(files)`

Important behavior:

- `IndexManager` first checks `shouldIndex(file)` to avoid doing work for file types no index supports.
- writes run on a single background writer thread (`Index-Writer`) to keep DB writes consistent.

### Incremental updates

When a file is re-indexed:

1. `IndexDefinition.map(file, helper)` produces the new key/value entries.
2. previous keys for that file are looked up via the forward map.
3. old packets are removed from the inverted map (by matching `fileId`).
4. new packets are appended to the inverted map.
5. the forward map is updated to the new key set.
6. the DB transaction is committed.

### Batch indexing

For large classpath scans (thousands of `.class` files from `jrt:` / jars), per-file task scheduling and per-file commits are expensive.

Nanoj supports batching via:

- `IndexManager.updateFilesAsync(Iterable<FileObject>)`

This queues a single writer task that:

- indexes many files in one loop
- performs a **single `db.commit()`** at the end

Related configuration keys:

- `nanoj.indexing.traversalBatchSize`
  - batch size used when `IndexManager` traverses folder/jar/jrt roots
- `nanoj.indexing.precomputeParallelism`
  - parallelism for batch precompute work (map/serialization); writes still happen on the single writer
- `nanoj.indexing.jrt.skipNonExported`
  - default `true`
  - if enabled, JRT indexing skips packages that are not exported by their module (not accessible from normal code)

- `nanoj.indexing.skipUnchangedFiles`
  - default `true`
  - if enabled, Nanoj skips work for indexes that consider a file up-to-date
  - staleness is evaluated **per index** via `IndexDefinition.isOutdated(file, stamps)` (default: `lastModified` + `length`)

- `nanoj.indexing.schemaBackfillBatchSize`
  - deprecated (kept for compatibility)
  - input collection/submission is handled by `IndexingInputCollector` (not `IndexManager`)
  - when a new `IndexDefinition` is registered (or its version changes), Nanoj submits already-known files + project roots + libraries + boot classpath (+ optional `jrt:/modules`) to `IndexManager`,
    and `IndexManager` decides per-index staleness (so only the new/changed index runs for unchanged files)

### Per-index staleness and versions

Each `IndexDefinition` has:

- a stable `getId()` used to name on-disk structures
- a `getVersion()` integer

If an index's version changes, Nanoj marks that specific index as requiring rebuild; on registration/version-change,
indexing inputs are re-submitted (project roots + libraries + boot classpath + optional `jrt:/modules`) and `IndexManager`
decides what work is needed per index.

Separately from versioning, incremental updates may skip work for a given file/index pair if the index reports the
file is not outdated:

- `IndexDefinition.isOutdated(file, stamps)`
  - default implementation checks `lastModified` + `length` using `IndexingStampStore` **per index ID**
  - index implementations may override this to implement stronger or domain-specific invalidation

For convenience, new indexes may extend `AbstractIndex`.

## Dumb mode

“Dumb mode” is a state where indexing is running and some smart features may be degraded or deferred.

In Nanoj:

- indexing tasks start normally
- if an indexing task runs longer than a threshold, Nanoj enters dumb mode for that duration

Relevant code:

- `IndexManager.DUMB_THRESHOLD_MS_KEY` (property: `nanoj.indexing.dumbThresholdMs`)
  - default is 750ms
  - `<= 0` forces immediate dumb mode for indexing tasks

Why a threshold exists:

- many updates are fast; entering dumb mode instantly would be noisy and user-hostile
- the threshold prevents flicker by only entering dumb mode for “meaningful” indexing work

Progress reporting:

- `IndexManager` tracks queued/running/completed counts and a “current file path”
- listeners can subscribe via `addProgressListener(...)`

## Query APIs

Nanoj’s query path is designed to avoid allocating huge result lists unnecessarily.

Primary APIs:

- `processValues(indexId, key, scope, processor)`
  - exact-key lookup, streams values to `processor`

- `processPrefix(indexId, prefix, scope, processor)`
  - prefix lookup via B-Tree prefix range, streams values to `processor`

- `SearchScope`
  - a predicate deciding if a `fileId` should be included
  - example scopes live in `Scopes` (`Scopes.projectSource`, `Scopes.libraries`, `Scopes.all`)

Examples of consumers:

- Java class name completion uses `processPrefix(ShortClassNameIndex.ID, prefix, scope, ...)`
- `IndexedJavaFileManager` uses `processValues(JavaPackageIndex.ID, packageName, scope, ...)` to list types in a package

## Helpers and per-file reuse

Some indexers want to avoid re-reading/parsing the same file repeatedly.

Nanoj supports a per-file “helper” object:

- `IndexManager` may build a helper once per file and pass it to all indexers via `IndexDefinition.map(file, helper)`
- currently this is optimized for `.class` indexing: helper is `byte[]` of class contents

On the Java side there is also a small parsed-class cache:

- `lang-java/.../indexing/SharedClassFile.java`

This reduces repeated `ClassFile.read(...)` work across multiple binary indexers.

## Shared indexes (prebuilt, read-only)

Nanoj can mount one-or-more shared **read-only** index DB files.

Use cases:

- bundle a prebuilt JDK index so users don’t pay indexing cost on first run
- ship organization-wide indexes for common libraries

Configuration:

- property `nanoj.indexing.sharedIndexPaths`
- value: comma/semicolon separated list of absolute DB file paths

Behavior:

- queries read from local DB **and** shared DB(s) and union results
- indexing writes only update the local DB

See: `SHARED_INDEXES.md`

## Adding a new index

1) Implement `IndexDefinition<K, V>`

- choose a stable `getId()` (changing it invalidates existing caches)
- implement `supports(FileObject file)`
- implement `map(FileObject file, Object helper)`
- implement `serializeValue(V)` and `deserializeValue(byte[])`

2) Register it

- during language/plugin initialization call `IndexManager.register(def)`

3) Query it

- prefer `processValues` / `processPrefix` for streaming queries
- use `SearchScope` to restrict results to project/lib/boot

## Operational notes

- Indexes are best-effort and should tolerate malformed/partial sources.
- Keep value payloads compact: smaller packets mean faster IO and less DB bloat.
- Prefix-heavy indexes should prefer the B-Tree key-space and `processPrefix`.

## Related docs

- `SHARED_INDEXES.md`
