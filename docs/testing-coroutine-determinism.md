# Coroutine test determinism

A convention that prevents a class of flaky tests — specifically the one that let a Kotlin/Native failure reach `main` undetected (see post-mortem below).

## The rule

Any **production type that internally creates a `CoroutineScope`** for background work (collectors, pumps, reconciliation) MUST NOT hardcode a real dispatcher it then races in tests. Use one of:

- **Pattern A — injectable dispatcher (preferred):** take the dispatcher via constructor, defaulting to the production dispatcher. Example: `CompositeSeam(... , dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1))`, `scope = CoroutineScope(SupervisorJob() + dispatcher)`. Explicit and predictable in production; tests inject a test dispatcher.
- **Pattern B — inherit the caller's context:** `CoroutineScope(currentCoroutineContext() + SupervisorJob())` (as `NearbyLoom` does). The scope inherits whatever dispatcher the (suspend) caller is on, so under `runTest` it is automatically the test scheduler. Use when inheriting the caller's context is acceptable for production.

## The test rule

Construct such types under `runTest` with **`StandardTestDispatcher`** (FIFO at each virtual instant, single-threaded, on the test clock) — or `UnconfinedTestDispatcher` if the type has no concurrent timers or message flows where ordering matters. **Never** let a production real-thread dispatcher (`Dispatchers.Default`/`IO`, even `limitedParallelism(1)`) run under `runTest`: its work is decoupled from the virtual clock, so synchronous `.value` reads race it — passing on JVM and flaking on Native.

### `delay()` is already virtual — no production change needed

Any `TestDispatcher` constructed with no explicit scheduler binds to the enclosing `runTest`'s `TestCoroutineScheduler`, so every engine `delay()` (election timers, heartbeats) is virtual — a 5 s delay advances virtual time 5 s and consumes ~0 ms wall. No production timer change is required.

### The real risk is *ordering* under `UnconfinedTestDispatcher`

`UnconfinedTestDispatcher` runs continuations **eagerly inline**. At a single virtual instant the interleaving of a timer firing against an in-flight message round-trip depends on how many continuation steps the CPU happened to take — load-dependent ordering that produces flaky tests on Kotlin/Native.

`StandardTestDispatcher` is **FIFO at each virtual instant** (no eager inline execution), so the ordering of timers vs message round-trips is fixed and reproducible on any machine. Use it for any system with concurrent timers and messages (e.g. `RaftNode` election/heartbeat loops). The raft suite switched to `StandardTestDispatcher` for this reason in issue #383.

### `advanceUntilIdle()` is unsafe for never-quiescing systems

A system whose timers perpetually re-arm (election + heartbeat loops) never becomes idle — `advanceUntilIdle()` would spin forever. Use bounded `advanceTimeBy` steps or the `RaftSimulation.await*` helpers instead; they drive time in fixed increments and fail fast with a state dump if the cluster doesn't converge.

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

| Component | Dispatcher | Status |
|---|---|---|
| `CompositeSeam` / `CompositeLoom` | `UnconfinedTestDispatcher` (injected) | ✅ Pattern A |
| `NearbyLoom` | inherits caller | ✅ Pattern B |
| `RaftNode` (`:kuilt-raft` suite) | `StandardTestDispatcher` via `raftRunTest` | ✅ #383 — FIFO ordering required; see `RaftTestFixtures` banner |
| `KtorClientLoom`, `KtorServerLoom`, `WebRTCPeerLink`, Multipeer bridge (`BridgeBrowser`/`BridgeSessionState`) | hardcode `Dispatchers.Default`/`IO` | Not currently flaky (integration/loopback-tested, not virtual-time unit tests). **Migrate to Pattern A when next touched or if they gain `runTest` unit tests.** |
