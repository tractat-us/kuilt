# Coroutine test determinism

A convention that prevents a class of flaky tests — specifically the one that let a Kotlin/Native failure reach `main` undetected (see post-mortem below).

## The rule

Any **production type that internally creates a `CoroutineScope`** for background work (collectors, pumps, reconciliation) MUST NOT hardcode a real dispatcher it then races in tests. Use one of:

- **Pattern A — injectable dispatcher (preferred):** take the dispatcher via constructor, defaulting to the production dispatcher. Example: `CompositeSeam(... , dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1))`, `scope = CoroutineScope(SupervisorJob() + dispatcher)`. Explicit and predictable in production; tests inject a test dispatcher.
- **Pattern B — inherit the caller's context:** `CoroutineScope(currentCoroutineContext() + SupervisorJob())` (as `NearbyLoom` does). The scope inherits whatever dispatcher the (suspend) caller is on, so under `runTest` it is automatically the test scheduler. Use when inheriting the caller's context is acceptable for production.

## The test rule

Construct such types under `runTest` with **`UnconfinedTestDispatcher(testScheduler)`** (eager + single-threaded + on the test clock) — or `StandardTestDispatcher` if you want to drive time explicitly. **Never** let a production real-thread dispatcher (`Dispatchers.Default`/`IO`, even `limitedParallelism(1)`) run under `runTest`: its work is decoupled from the virtual clock, so synchronous `.value` reads race it — passing on JVM and flaking on Native. Injecting the test dispatcher also gives free coroutine-leak detection (`runTest` fails on uncompleted jobs in the test scope).

## Why — post-mortem (native flakiness, May 2026)

`CompositeSeam` owns a scope running per-ply collectors. Its early tests constructed it with the production real-thread dispatcher. On JVM the background reconciliation usually completed before assertions; on Kotlin/Native the timing differed and `CompositeSendReceiveTest` intermittently failed with "expected exactly one frame but got none." It slipped to `main` because:

1. **CI runs on `ubuntu-latest`** — Apple-native targets (`macosArm64Test`, `iosSimulatorArm64Test`) need macOS, so the required `build` check **never executed the native tests**. The gate was green on a build that couldn't run them.
2. **A cached `BUILD SUCCESSFUL` masks intermittent failures** — only `./gradlew build --rerun-tasks` (fresh) exposed it.
3. The tests used the **production dispatcher** under `runTest` (the class this convention forbids).

The fix (PR #82) injected `UnconfinedTestDispatcher(testScheduler)` into the composite tests; deterministic on all targets since.

## Practical guards (given CI does not run native)

- **Run native locally before merging** anything touching coroutine-scoped components: `./gradlew build` on a Mac runs the Apple targets. Use `--rerun-tasks` to defeat cached-green masking when verifying a suspected flake.
- Prefer Pattern A/B for all **new** scope-owning types.

## Existing components

| Component | Status |
|---|---|
| `CompositeSeam` / `CompositeLoom` | ✅ Pattern A (injectable) |
| `NearbyLoom` | ✅ Pattern B (`currentCoroutineContext()`) |
| `KtorClientLoom`, `KtorServerLoom`, `WebRTCPeerLink`, Multipeer bridge (`BridgeBrowser`/`BridgeSessionState`) | Hardcode `Dispatchers.Default`/`IO`. Not currently flaky (integration/loopback-tested, not virtual-time unit tests). **Migrate to Pattern A when next touched or if they gain `runTest` unit tests.** |
