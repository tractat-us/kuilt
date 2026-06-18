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

### `advanceTimeBy(largeSpan)` over an always-on timer is O(span/interval) real work

Even bounded, `advanceTimeBy` is not free over a re-arming timer loop. Virtual time is cheap only because *nothing happens between* scheduled tasks — but an always-on timer (a heartbeat every `interval`) schedules a task at *every* multiple of `interval` across the span. Advancing a large span forces the scheduler to run **every** intervening fire: the cost is `span / interval` actual continuations of real CPU work, not a constant-time clock bump. With a 2 ms test heartbeat, `advanceTimeBy(5.minutes)` is ~150 000 heartbeat iterations — enough to blow `runTest`'s wall-clock dispatch timeout and surface as `UncompletedCoroutinesError: the test body did not run to completion` (a *wall-clock* timeout, not a virtual-time one). The symptom looks like a hang; the cause is "I asked the scheduler to do 150k iterations of work."

Guidance:

- **Advance the smallest span that proves the property.** If a test only needs "some time passed," advance one or two timer intervals, not minutes. Most properties don't depend on the *magnitude* of elapsed virtual time at all — advancing time to "prove" something that has no timer behind it is inert (and pure cost). Reach for `advanceTimeBy` only when a specific timer must fire.
- **Prefer event-driven waits over time-driven ones.** Await the state you actually want (`role.first { … }`, `RaftSimulation.await*`) rather than advancing a large span and hoping. The await converges in the minimum virtual time and skips the intervening churn.
- **A timeout here means "too much scheduled work," not "deadlock."** Before widening the `runTest` timeout, check whether an `advanceTimeBy(largeSpan)` (or an `advanceUntilIdle()`) is grinding through a re-arming timer. Shrink the span; don't widen the timeout. (Receipt: a #586 return-at-quorum test advanced 5 virtual minutes purely to "show time passed" with no timer behind the assertion — it hung CI at the 2 ms heartbeat and was deleted, the property documented instead.)

## Why — post-mortem (native flakiness, May 2026)

`CompositeSeam` owns a scope running per-ply collectors. Its early tests constructed it with the production real-thread dispatcher. On JVM the background reconciliation usually completed before assertions; on Kotlin/Native the timing differed and `CompositeSendReceiveTest` intermittently failed with "expected exactly one frame but got none." It slipped to `main` because:

1. **CI runs on `ubuntu-latest`** — Apple-native targets (`macosArm64Test`, `iosSimulatorArm64Test`) need macOS, so the required `build` check **never executed the native tests**. The gate was green on a build that couldn't run them.
2. **A cached `BUILD SUCCESSFUL` masks intermittent failures** — only `./gradlew build --rerun-tasks` (fresh) exposed it.
3. The tests used the **production dispatcher** under `runTest` (the class this convention forbids).

The fix (PR #82) injected `UnconfinedTestDispatcher(testScheduler)` into the composite tests; deterministic on all targets since.

## Diagnosing a cross-test `UncaughtExceptionsBeforeTest` (the #535 class)

A scope-owning type whose scope **outlives `runTest`** (a `CompositeLoom`/`SeamReplicator`/`SeamRoom` the test never `close()`s) can leak an uncaught coroutine exception. `runTest` doesn't lose it — it reports it as `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` against **whatever test happens to run next**. The symptom is a test that "fails flakily" with no obvious connection to its own body, and that passed minutes earlier on unrelated commits. The named test is the *victim*, not the culprit.

**Do not trust the symptom test.** The real cause is in the `<failure>` element's **suppressed** exception in the report XML, which names the actual throwing site:

```
<failure message="...UncaughtExceptionsBeforeTest...">
  ...
  Suppressed: java.lang.IllegalStateException: Seam for PeerId(value=peer-1) is closed
    at us.tractat.kuilt.core.InMemorySeam.broadcast(InMemoryLoom.kt:128)
    at us.tractat.kuilt.core.composite.CompositeSeam$attachPly$2.invokeSuspend(CompositeSeam.kt:154)   ← the real culprit
```

How to read it:

- The console / Gradle summary only shows `N tests completed, 1 failed` — pull the real message from the report XML (`**/build/test-results/<target>Test/TEST-*.xml`). The `$attachPly$2`-style frame names the exact pump and line.
- On a **red `main`**, download the *failed attempt's* artifacts — a re-run replaces them, and `gh api .../artifacts/<id>/zip` 404s. Use `gh run download <run-id> --repo <org>/<repo>` for the latest, and `gh api repos/<org>/<repo>/actions/runs/<run-id>/attempts/1 --jq .conclusion` to confirm which attempt failed.

The root-cause class is almost always a **best-effort fabric send on a fabric that tore underneath the sender**. The `Seam` contract throws `IllegalStateException` on a send while `Torn`, and a ply/transport can tear at any time — from a remote disconnect, not just local `close()`. Any internal send a scope-owning type makes that is *not* directly requested by the caller (announces, heartbeats, re-broadcasts) MUST be wrapped:

```kotlin
runCatchingCancellable { seam.broadcast(frame) }   // rethrows cancellation, swallows the torn-ply throw
```

A `seam.state.value is Woven` guard before the send is **not** sufficient — it's a TOCTOU: the ply can tear between the guard and the send. Tolerate the throw at the send site. (#535 fixed `CompositeSeam`'s two announce pumps this way; PR #538.)

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
