# Project services in Nanoj (`ProjectServiceManager`)

This document explains Nanoj’s project-scoped service container:

- what it’s for (and what it is not)
- how services and “extensions” are created and cached
- lifecycle & disposal
- thread-safety expectations

> Design note
>
> The concept is **inspired by IntelliJ IDEA’s project/application services** and the platform’s service/extension-point approach: services are lazily created singletons scoped to a project, and extension points allow multiple implementations. Nanoj’s implementation is intentionally lightweight and uses reflection + a small registry.

## What problem it solves

Nanoj needs a way to wire together core subsystems (indexing, editor, completion, compiler helpers, etc.) without:

- global singletons (which break multi-project isolation)
- eager initialization at startup (bad for latency and memory on mobile)
- a heavyweight DI framework (extra allocations/overhead)

`ProjectServiceManager` provides:

- **Project scope**: each `Project` gets its own container
- **Lazy creation**: instances are created on first access
- **Simple DI**: constructor injection via `public (Project)`
- **Lifecycle**: deterministic cleanup via `Disposable`

## Core API

File: `core/src/main/java/com/tyron/nanoj/core/service/ProjectServiceManager.java`

### Services (singleton per project)

- `getService(project, SomeService.class)`
  - returns the project’s singleton instance
  - creates it lazily on first call

Bindings:

- `registerBinding(project, Interface.class, Impl.class)`
  - binds an interface to an implementation class
  - must be called **before** the service is instantiated

- `registerBindingIfAbsent(project, Interface.class, Impl.class)`
  - convenience for “register from multiple entry points”
  - does nothing if already bound or already instantiated

Instance registration:

- `registerInstance(project, SomeService.class, instance)`
  - directly installs an already-created instance

### Extensions (multi-instance per project)

Extensions are for “multiple implementations of a point”, e.g. language supports or detectors.

- `registerExtension(project, ExtensionPoint.class, Impl.class)`
  - registers an implementation class for the extension point

- `getExtensions(project, ExtensionPoint.class)`
  - returns an unmodifiable list of lazily-created instances
  - instances are cached after first creation

### Project disposal

- `disposeProject(project)`
  - removes the project’s container
  - disposes services and instantiated extensions that implement `Disposable`

Also note:

- `getService(...)` and `getExtensions(...)` require `project.isOpen() == true`.
  Attempting to access services for a closed project throws.

## How it works internally

### One container per project

`ProjectServiceManager` keeps:

- `Map<Project, ServiceContainer> projectContainers`

A `ServiceContainer` owns all state for a single project.

### Service caching

Services are stored in:

- `Map<Class<?>, Object> services`

When you call `getService(project, Foo.class)`:

1. it finds the `ServiceContainer` for the project (creates it if missing)
2. it calls `services.computeIfAbsent(Foo.class, ...)`
3. it instantiates the implementation class and stores it

Bindings are stored in:

- `Map<Class<?>, Class<?>> serviceBindings`

So calling `getService(project, IFoo.class)` will instantiate the bound class if present.

### Extension caching

Extension definitions are stored in:

- `Map<Class<?>, List<Class<?>>> extensionDefinitions`

The instantiated extension list cache is:

- `Map<Class<?>, List<Object>> extensionCache`

Calling `getExtensions(project, Point.class)`:

- lazily instantiates each registered implementation class
- stores the unmodifiable list in `extensionCache`

If you register a new extension implementation at runtime, the cache for that point is invalidated.

### Instantiation rule (constructor injection)

All services and extensions are instantiated via reflection and must provide:

- `public <ServiceOrExtension>(Project project)`

If it is missing, Nanoj throws `ServiceInstantiationException`.

### Disposal

When `disposeProject(project)` is called:

1. dispose all instantiated services that implement `Disposable`
2. dispose all instantiated extensions that implement `Disposable`
3. clear the container maps

Disposal errors are caught and printed to stderr so one misbehaving service doesn’t prevent cleanup.

## Thread-safety expectations

`ProjectServiceManager` uses concurrent maps and `computeIfAbsent`, so **access is safe across threads** in the sense that it won’t corrupt the container.

However:

- Service constructors should assume they may run on whatever thread first requests them.
- If a service requires a specific thread (e.g., UI thread), it should enforce that internally.
- Extensions use a synchronized list for registrations (`Collections.synchronizedList`) and cache the instantiated list.

## Recommended usage patterns

### Prefer interfaces at call sites

Consumers should request by interface where appropriate:

- `ProjectServiceManager.getService(project, DumbService.class)`

And wire the binding during initialization:

- `registerBinding(project, DumbService.class, DumbServiceImpl.class)`

### Keep constructors cheap

Because services are lazily created, constructors are on the “hot path” for first-time feature use.

- avoid expensive IO in constructors
- avoid scanning the filesystem
- prefer lazy initialization inside the service

### Make cleanup explicit

If a service owns resources (threads, file handles, DBs):

- implement `Disposable`
- release resources in `dispose()`

Then ensure the project’s disposal path calls:

- `ProjectServiceManager.disposeProject(project)`

## Relationship to IntelliJ concepts

Nanoj’s mapping is conceptually similar:

- **Project service** (IntelliJ) → `getService(project, X.class)` (Nanoj)
- **Binding / implementation** → `registerBinding(...)`
- **Extension point** → `registerExtension(...)` + `getExtensions(...)`
- **Disposal** → `Disposable` lifecycle on project close

The goal is the same: keep IDE subsystems modular, lazily initialized, and scoped to the project.
