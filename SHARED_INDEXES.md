# Shared Indexes

Shared indexes are **read-only** MapDB index files that Nanoj can mount in addition to the project’s normal writable index.

They’re intended to let users **skip indexing common/expensive inputs** (notably the JDK / `jrt:`) and to let you **bundle prebuilt indexes**.

## How it works

- Nanoj keeps a **local writable** index DB under the project cache directory (currently `nanoj_index.db`).
- You can mount one or more **shared read-only** index DBs.
- Searches (`processValues`, `processPrefix`) read from:
  - local DB, then
  - each shared DB
  and **union** the results.
- Writes/indexing updates always go to the **local** DB only.

### File IDs

Shared DBs have their own internal `fileId` space. When emitting results from a shared DB, `IndexManager` encodes the shared result file ID into a “global” ID:

- bit 31 indicates “shared result”
- bits 24..30 are the shared-store ordinal (0..127)
- bits 0..23 are the shared DB’s local fileId (0..16,777,215)

The caller can resolve paths via `IndexManager.getFilePath(fileId)` and does not need to know whether an ID is local or shared.

## Configuring shared indexes

Set the project configuration property:

- `nanoj.indexing.sharedIndexPaths`

Value format:

- a **comma or semicolon** separated list of **absolute paths** to shared MapDB files

Example:

```properties
# Use a bundled JDK index plus a company-wide library index
nanoj.indexing.sharedIndexPaths=/opt/nanoj/indexes/jdk21-linux.db,/opt/nanoj/indexes/company-libs.db
```

Notes:

- If a path does not exist (or is not a file), it is ignored.
- If a DB is incompatible or missing required maps, it is skipped best-effort.
- Shared DBs are opened with MapDB `readOnly()`.

## Required contents of a shared index DB

A shared index DB must contain the same schema Nanoj uses:

- `sys_id_to_path` (required for resolving search results)
- `sys_path_to_id`
- `sys_seq_file_id`
- For each index definition `indexId` you want to serve:
  - `${indexId}_inv`
  - `${indexId}_fwd`

If a shared DB does not include a given `indexId`, Nanoj will simply not use that DB for that index.

## Building a shared index DB

Nanoj includes a small helper utility:

- `core/src/main/java/com/tyron/nanoj/core/indexing/SharedIndexBuilder.java`

It can build a standalone shared index DB given:

- an output file path
- a list of `FileObject`s to index
- the `IndexDefinition`s to include

### Example (code snippet)

Below is an example of how you might build a shared index file from your own tool/test:

```java
import com.tyron.nanoj.core.indexing.SharedIndexBuilder;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.api.vfs.FileObject;

import java.io.File;
import java.util.List;

public final class BuildIndexes {
  public static void main(String[] args) {
    File out = new File("/tmp/jdk-shared.db");

    List<FileObject> files = /* collect .class files from jrt:/, jars, etc */;
    List<IndexDefinition<?, ?>> defs = List.of(
      /* e.g. new JavaBinaryStubIndexer(), new ShortClassNameIndex(), new JavaPackageIndex() */
    );

    SharedIndexBuilder.build(out, files, defs);
  }
}
```

Practical guidance:

- For a JDK shared index, you typically collect `.class` files from `jrt:/modules/...`.
- Ensure you use the **same indexer versions** as the Nanoj build that will consume the shared DB.

## Bundling shared indexes

Common approaches:

- Ship a shared DB alongside the app and point `nanoj.indexing.sharedIndexPaths` at it.
- Allow users to download indexes (e.g., by JDK version) and add them to the config.

## Compatibility / caveats

- Shared DBs should be treated as **versioned artifacts**.
  - If you change index serialization (`IndexDefinition.serializeValue/deserializeValue`) or index IDs, rebuild shared DBs.
- If a shared DB doesn’t contain the same index IDs as the running app, you’ll simply get fewer results (no crash expected).
- Because results are a union of local + shared, duplicates may exist if the same symbol is indexed in both.

## Troubleshooting

- “I set `nanoj.indexing.sharedIndexPaths` but results don’t change”
  - Verify the path is absolute and points to a file.
  - Ensure the shared DB includes `${indexId}_inv`/`${indexId}_fwd` and `sys_id_to_path`.
- “Paths from shared results can’t be resolved”
  - The shared DB is missing `sys_id_to_path` or it was built with a different layout.
