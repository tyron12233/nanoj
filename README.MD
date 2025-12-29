# NanoJ

NanoJ is an experimental, modular editor/runtime core focused on **mobile-first constraints** (thermal throttling, flash I/O) while still delivering persistent indexing, fast code completion, and incremental background work, inspired by approaches from IntelliJ and NetBeans.

This repo is a research/active-development codebase; APIs and behavior will change.

## Highlights

### How Java completion works

Java completion combines **persistent indexes** (fast lookups) with **javac** while keeping expensive compiler infrastructure reusable.

- **Indexes (via [`IndexManager`](core/src/main/java/com/tyron/nanoj/core/indexing/IndexManager.java))** power the fast “search space”:
	- [`ShortClassNameIndex`](lang-java/src/main/java/com/tyron/nanoj/lang/java/indexing/ShortClassNameIndex.java) (`java_short_names`) for class name lookup across sources + binaries
	- [`JavaPackageIndex`](lang-java/src/main/java/com/tyron/nanoj/lang/java/indexing/JavaPackageIndex.java) (`java_packages`) for package/type discovery in `import` and qualified references
	- [`JavaSuperTypeIndex`](lang-java/src/main/java/com/tyron/nanoj/lang/java/indexing/JavaSuperTypeIndex.java) (`java_supertypes`) for smart `new` suggestions (expected-type → subtypes)
	- [`JavaBinaryStubIndexer`](lang-java/src/main/java/com/tyron/nanoj/lang/java/indexing/JavaBinaryStubIndexer.java) (`java_stubs`) for lightweight library metadata

- **Stubs**: `JavaBinaryStubIndexer` builds [`ClassStub`](lang-java/src/main/java/com/tyron/nanoj/lang/java/indexing/stub/ClassStub.java) records from `.class` files so completion can inspect library members, signatures, and access flags without loading classes.

- **Cached Javac file manager**: [`JavacFileManagerService`](lang-java/src/main/java/com/tyron/nanoj/lang/java/compiler/JavacFileManagerService.java) is a project service that holds the heavy `JavaFileManager` (wrapped by [`IndexedJavaFileManager`](lang-java/src/main/java/com/tyron/nanoj/lang/java/compiler/IndexedJavaFileManager.java)) so the boot/classpath scan happens once per project.

- **Per-request compilation snapshot**: when type info is needed, [`CompilationInfo`](lang-java/src/main/java/com/tyron/nanoj/lang/java/compiler/CompilationInfo.java) creates a javac task for the current text snapshot and reuses the shared file manager.

### Indexing

- Persistent MapDB-backed indexes with **schema/version invalidation** and staleness clearing.
- Shared read-only indexes ([`SHARED_INDEXES.md`](SHARED_INDEXES.md)) support (mount/bundle prebuilt indexes).
- Index queries support both value iteration and **key-aware prefix** processing (used for package completion).
- “Dumb mode” gating for features that should wait for indexing.

Docs: [`INDEXING.md`](INDEXING.md), [`SHARED_INDEXES.md`](SHARED_INDEXES.md)

### Code completion (Java)

- **Member reference** completion (`expr.<caret>`) with correct generic substitution (e.g. `List<String>.get()` returns `String`).
- **Import / qualified reference** package completion (`import java.util.<caret>` and `java.util.<caret>`).
- **Smart `new` suggestions**: expected-type inference + subtype suggestions (e.g. `List<String> xs = new <caret>` suggests `ArrayList`).
- **Insert handlers**:
	- methods insert `()` and place the caret sensibly
	- `new` completions can add imports, add `<>` for generics, add `()`, and place the caret for ctor args
- Sorting improvements via **lookup weighers** (e.g. exact-prefix matches first).

Java completion tests live under [`lang-java/src/test/java/com/tyron/nanoj/lang/java/completion/`](lang-java/src/test/java/com/tyron/nanoj/lang/java/completion/).

### Diagnostics / error highlighting

- Language-agnostic diagnostics API in `:api`.
- Java diagnostics provider backed by javac diagnostics.
- Desktop RSyntaxTextArea notice integration (current UI/testing surface).

## Mobile-first direction

NanoJ’s architecture is designed to work well on mobile:

- Background work is explicit and incremental-friendly (indexing, scanning).
- File access is abstracted via a VFS layer (`file:`, `jar:`, `jrt:`).
- Services/extensions are project-scoped and lazily initialized.

Note: the `:desktop` module is currently **for testing and prototyping** (UI + integration surface), not the long-term product target.

## Modules

- `:api` — public interfaces (VFS, indexing, editor, diagnostics, project services)
- `:core` — implementations (indexing, VFS backends, editor core, completion infra)
- `:lang-java` — Java integration (javac helpers, `.class` indexing, Java completion)
- `:test-framework` — deterministic test harness (mock FS/VFS, project setup, completion/editor bases)
- `:desktop` — testing/prototyping module (non-target runtime)

## Docs

- [`API.md`](API.md)
- [`VIRTUAL_FILE_MANAGER.md`](VIRTUAL_FILE_MANAGER.md)
- [`EDITOR_API.md`](EDITOR_API.md)
- [`PROJECT_SERVICES.md`](PROJECT_SERVICES.md)
- [`INDEXING.md`](INDEXING.md)
- [`SHARED_INDEXES.md`](SHARED_INDEXES.md)
- [`TESTING.md`](TESTING.md)

## Build & test

```bash
./gradlew test
```

Run a single test class:

```bash
./gradlew :lang-java:test --tests com.tyron.nanoj.lang.java.completion.JavaNewClassInsertHandlerTest
```

## Writing tests (quick guide)

Start from the closest base class in `:test-framework`:

- [`BaseIdeTest`](test-framework/src/main/java/com/tyron/nanoj/testFramework/BaseIdeTest.java) → VFS/project-scoped service tests
- [`BaseEditorTest`](test-framework/src/main/java/com/tyron/nanoj/testFramework/BaseEditorTest.java) → document/editor infrastructure
- [`BaseCompletionTest`](test-framework/src/main/java/com/tyron/nanoj/testFramework/BaseCompletionTest.java) → completion infrastructure + project lifecycle
- Java completion tests typically extend `lang-java`’s `BaseJavaCompletionTest` or `BaseJavaTypingCompletionTest`

Example (typing simulation):

```java
class MyTest extends BaseJavaTypingCompletionTest {
	@Test
	void completesSomething() throws Exception {
		try (TypingSession s = typingSession("p.Foo", """
			package p;
			public class Foo { void test() { new Arr<caret> } }
			""")) {
			var r = s.complete();
			var el = requireLookup(r.items, "ArrayList");
			// apply completion + assert document/caret
		}
	}
}
```

More details (how Mock VFS/projects work, index flushing, dumb mode): [`TESTING.md`](TESTING.md).

## Feature & test TODO (completed + planned)

### Code completion (Java)

- [x] Member reference completion (`expr.<caret>`)
- [x] Generic substitution in member select (e.g. `List<String>` members)
- [x] Suppress `this.` / `super.` completions in static contexts
- [x] Method insertion handler (adds `()` + caret placement)
- [x] Smart `new` completion (expected type inference + subtype suggestions)
- [x] Prefer instantiable types over abstract/interface in `new` ranking
- [x] `new` insert handler: add imports, add `<>` for generics, add `()`, avoid duplicating existing `<>()`, caret placement for ctor args
- [x] Keyword completion + keyword insertion spacing
- [x] Package completion for import + qualified references
- [x] Lookup sorting: exact match prioritized

- [ ] Identifier completion in more contexts (locals/fields/methods/types) + better contextual filtering
- [ ] Overload-aware ranking + parameter-aware completion scoring
- [ ] Parameter info (signature help) and argument hints
- [ ] Javadoc/quick documentation surface for completion items
- [ ] Snippets / live templates (e.g. `for`, `if`, `try-with-resources`)
- [ ] Completion in more syntactic positions (annotations, `extends/implements`, `throws`, `new` with qualifying expr)

### API

- [x] VFS layer (`file:`, `jar:`, `jrt:`) + unified file events
- [x] Editor model (`Document`, `Editor`, `FileDocumentManager`)
- [x] Project-scoped services + extension points
- [x] Diagnostics API + Java diagnostics provider

- [ ] Stabilize `:api` (versioning strategy + compatibility policy)
- [ ] Public extension points for language plugins (completion/diagnostics/indexing hooks)
- [ ] Navigation APIs (go-to definition, find usages)
- [ ] Formatting/organize-imports API surface

### Indexing

- [x] Persistent indexes + forward/inverted layout
- [x] Index schema/version invalidation + staleness clearing
- [x] Shared indexes mounting (read-only)
- [x] Java package index + key-aware prefix processing for package completion
- [x] Supertype → subtype index (used by smart `new`)

- [ ] Incremental reindexing on edits (granular invalidation for source changes)
- [ ] Index compaction/cleanup strategies + cache size controls
- [ ] Better scheduling/prioritization (foreground queries vs background scans)
- [ ] Diagnostics-driven indexing health checks (corruption detection, rebuild UX)

### Test cases (what we cover / what to add)

- [x] Member select completion: instance members + generics substitution (`JavaMemberSelectCompletionTest`)
- [x] `new` expected-type suggestions + ranking (`JavaNewExpressionCompletionTest`)
- [x] Package completion in import/qualified contexts (`JavaPackageCompletionTest`)
- [x] Insert handler behavior for `new` (imports/diamond/parens/caret) (`JavaNewClassInsertHandlerTest`)
- [x] Keyword suggestion + keyword insert spacing (`JavaKeywordCompletionTest`, `JavaKeywordInsertHandlerTest`)
- [x] Method insert handler (`JavaCompletionMethodInsertHandlerTest`)

- [ ] Completion “no regressions” matrix across contexts (statement, expression, class body, top-level)
- [ ] Negative tests (ensure no suggestions in invalid contexts; ensure no duplicate imports)
- [ ] Sorting tests covering combined weighers + priority interactions
- [ ] Index invalidation tests (schema bump clears stale data; shared-index fallback)
- [ ] Diagnostics tests for edge cases (syntax errors, partial trees, multi-file errors)

## Contributing

High-impact areas:

- indexing performance/correctness and incremental invalidation
- Java completion correctness + insertion behavior
- mobile-focused runtime constraints (memory, IO patterns)
- test coverage (especially negative and regression tests)