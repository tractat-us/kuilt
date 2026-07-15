# Voter-mesh reconnection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each Task is one PR.

**Goal:** Make a formed `WebSocketVoterMesh` heal a dropped inter-server link — re-dial forever with exponential backoff — instead of leaving the edge permanently severed.

**Architecture:** Keep each voter's mesh on `hubMesh` (never terminal on drain). Add two reusable `kuilt-core` primitives (`ExponentialBackoff`, `acceptPump`) and a transport-agnostic `VoterReconnectionSupervisor` in `kuilt-cluster` that runs one per-peer `collectLatest` redial loop per dialed voter. `voterMeshOverWebSockets` composes them, preserving synchronous formation. Prerequisite #1452 (send-failure conn-guard) is already merged.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kotlinx-atomicfu, Ktor (WebSocket), kuilt.kmp-library convention.

## Global Constraints

- `explicitApi()` is enforced — every public declaration needs an explicit visibility modifier. New `kuilt-core` primitives are `public`; `VoterReconnectionSupervisor` is `internal`.
- Test methods: no `test` prefix (the `@Test` annotation suffices); multi-assert tests use `assertAll()`.
- Coroutine determinism: inject `CoroutineContext`/dispatcher and `Random` — never call wall clock / unseeded RNG. **Test idiom (exact — the plan snippets below use it): `runTest(StandardTestDispatcher(), timeout = 5.seconds) { … }`** (never `StandardTestDispatcher(testScheduler)` — `testScheduler` is a `TestScope` member, unresolved at the call site; matches `MuxServerLoomLifecycleTest.kt:81`). Advance virtual time in **bounded** steps (`advanceTimeBy` + `runCurrent`), **never** `advanceUntilIdle()` on re-arming loops. No production dispatchers (`Dispatchers.{Default,IO,Unconfined,Main}`, `GlobalScope`) in test sources — the sole exception is Task 5's real-socket integration test, which carries an inline `@Suppress` + one-line reason.
- **`assertAll` is `us.tractat.kuilt.test.assertAll`** (`kuilt-test/.../Assertions.kt:8`), reachable from every module's commonTest via the `:kuilt-test` dep — NOT `org.junit.jupiter.api.assertAll` (JVM-only, won't compile in commonTest).
- Exception discipline: `runCatchingCancellable { … }` (from `us.tractat.kuilt.core`), **never** bare `runCatching`, in suspend/coroutine contexts. A `catch` that tolerates failure must rethrow `CancellationException`.
- Thread-safety via explicit primitives (atomicfu `reentrantLock` / atomics) — never `limitedParallelism(1)` confinement. Correctness must hold under a multi-threaded dispatcher.
- `kuilt-core` is **logger-free** — surface per-link failures through injected callbacks, never a logger.
- Verify before merge: `./gradlew build detektAll --rerun-tasks` (full build; `detektAll` not `detekt`; confirm `EXECUTED` not `FROM-CACHE`). A `:kuilt-core` change ripples to `:examples`/`:kuilt-cluster` E2E.
- PRs: open **ready** (not draft — draft→ready CI race); `gh pr merge <n> --auto --squash`.

---

### Task 1: `ExponentialBackoff` (kuilt-core)

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/util/ExponentialBackoff.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/util/ExponentialBackoffTest.kt`

**Interfaces:**
- Produces: `public class ExponentialBackoff(base: Duration, cap: Duration, factor: Double = 2.0, random: Random)` with `public fun delay(attempt: Int): Duration` — full-jitter: uniform in `[0, min(cap, base·factorᵃᵗᵗᵉᵐᵖᵗ))`. `attempt` is 0-based; the caller owns the counter.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import us.tractat.kuilt.test.assertAll

class ExponentialBackoffTest {

    @Test
    fun fullJitterStaysWithinTheGrowingCeilingAndIsCapped() {
        val backoff = ExponentialBackoff(base = 100.milliseconds, cap = 10.seconds, factor = 2.0, random = Random(42))
        assertAll(
            // attempt 0: ceiling = 100ms → delay ∈ [0, 100ms)
            { assertTrue((0..999).all { backoff.delay(0) < 100.milliseconds }) },
            // attempt 6: ceiling = 100ms·2^6 = 6.4s → delay ∈ [0, 6.4s)
            { assertTrue((0..999).all { backoff.delay(6) < 6_400.milliseconds }) },
            // attempt 100: ceiling clamps to cap = 10s → delay ∈ [0, 10s), never NaN/overflow
            { assertTrue((0..999).all { backoff.delay(100) < 10.seconds }) },
        )
    }

    @Test
    fun deterministicUnderASeededRandom() {
        val a = ExponentialBackoff(100.milliseconds, 10.seconds, random = Random(7))
        val b = ExponentialBackoff(100.milliseconds, 10.seconds, random = Random(7))
        assertTrue((0..20).all { a.delay(it) == b.delay(it) })
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :kuilt-core:jvmTest --tests "*ExponentialBackoffTest*"` → FAIL (unresolved `ExponentialBackoff`).

- [ ] **Step 3: Implement**

```kotlin
package us.tractat.kuilt.core.util

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Full-jitter exponential backoff. Stateless: [delay] is a pure function of the 0-based [attempt],
 * so the caller keeps the attempt counter (it resets naturally each retry episode). Randomness is an
 * injected dependency — pass a seeded [Random] in tests for determinism.
 *
 * The delay for an attempt is uniform in `[0, min(cap, base · factor^attempt))` ("full jitter",
 * AWS-style) — decorrelating many simultaneous retriers so a shared-transport blip that flaps N
 * edges at once does not produce a synchronized reconnect storm.
 */
public class ExponentialBackoff(
    private val base: Duration,
    private val cap: Duration,
    private val factor: Double = 2.0,
    private val random: Random,
) {
    init {
        require(base > Duration.ZERO) { "base must be positive, was $base" }
        require(cap >= base) { "cap ($cap) must be >= base ($base)" }
        require(factor >= 1.0) { "factor must be >= 1.0, was $factor" }
    }

    /** Full-jitter delay for [attempt] (0-based). Never negative; clamped to [cap]; overflow-safe. */
    public fun delay(attempt: Int): Duration {
        // base * factor^attempt overflows to Duration.INFINITE for large attempt; minOf clamps it.
        val ceiling = minOf(base * factor.pow(attempt), cap)
        return ceiling * random.nextDouble()
    }
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :kuilt-core:jvmTest --tests "*ExponentialBackoffTest*"` → PASS.
- [ ] **Step 5: Full gate** — `./gradlew :kuilt-core:build detektAll --rerun-tasks` → EXECUTED, green. (Pure common code; module build sufficient here.)
- [ ] **Step 6: Commit** — `git commit -m "feat(core): ExponentialBackoff — full-jitter, stateless, injected Random"`

---

### Task 2: `acceptPump` (kuilt-core) + migrate `hostedOverlay` and `MuxServerLoom`

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/AcceptPump.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/AcceptPumpTest.kt`
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxServerLoom.kt` (the `acceptLoop`, ~line 186)
- Modify: `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/HostedOverlay.kt` (the `while (isActive) { addLink(source.accept()) }` in `hostedMesh`, ~line 155-165)

**Interfaces:**
- Produces:
  ```kotlin
  public fun CoroutineScope.acceptPump(
      source: ConnectionSource,
      handshakeTimeout: Duration,
      onFailure: (Throwable) -> Unit = {},
      handle: suspend (Connection) -> Unit,
  ): Job
  ```
  A persistent pump: drains `source.accept()` forever; each accepted conn is handled **concurrently** (own child coroutine) under `handshakeTimeout`. On handle failure or timeout it invokes `onFailure` and closes the conn; on success it leaves the conn live. `handle` is the per-conn action — `{ mesh.addLink(it) }` for a single-mesh host, `{ admit(it) }` for `MuxServerLoom`.
- **Rationale for the `handle` lambda (not a fixed `mesh`):** `hostedOverlay`/voters `addLink` to one shared mesh, but `MuxServerLoom.admit` builds a *per-conn* seam — a fixed `mesh.addLink` signature wouldn't serve both. The `handle` lambda generalizes across all three consumers.

- [ ] **Step 1: Write the failing test** (the wedge regression — a hung handshake must NOT starve other conns)

```kotlin
package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class AcceptPumpTest {

    /** A conn whose handling hangs must not block a later conn's handling (concurrency), and must be
     *  abandoned after the handshake timeout (no permanent wedge). */
    @Test
    fun aHungHandshakeDoesNotStarveLaterConnections() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val handled = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()   // never completed → conn "hangs"
        val conns = ArrayDeque(listOf("hang", "good-1", "good-2"))
        val source = object : ConnectionSource {
            override suspend fun accept(): Connection {
                val id = conns.removeFirstOrNull() ?: CompletableDeferred<Connection>().await()
                return FakeConnection(id)   // test double: id-tagged
            }
        }
        val failures = mutableListOf<Throwable>()
        val job = acceptPump(source, handshakeTimeout = 2.seconds, onFailure = { failures += it }) { conn ->
            val id = (conn as FakeConnection).id
            if (id == "hang") gate.await()      // hangs forever
            else handled += id
        }
        // Bounded advance: past the 2s handshake timeout so the hung conn is abandoned.
        advanceTimeBy(3.seconds); runCurrent()
        assertEquals(setOf("good-1", "good-2"), handled.toSet())   // both good conns handled despite the hang
        job.cancel()
    }
}
```
(Define a minimal `FakeConnection(val id: String)` implementing `Connection` in the test file, or reuse `connectionPair`-style helpers; the send/incoming bodies can be no-ops for this test.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew :kuilt-core:jvmTest --tests "*AcceptPumpTest*"` → FAIL (unresolved `acceptPump`).

- [ ] **Step 3: Implement `acceptPump`**

```kotlin
package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Duration

/**
 * Persistent, concurrent, handshake-timed accept loop. Drains [source] forever; each accepted
 * [Connection] is handled in its **own** child coroutine under [handshakeTimeout], so one conn that
 * TCP-connects but never completes its handshake can never starve later conns (the sequential
 * `while { handle(accept()) }` pattern wedges — this replaces it).
 *
 * On success the conn is left live (owned by [handle], e.g. now in a mesh). On [handle] failure or a
 * handshake timeout, [onFailure] is invoked and the conn is closed. `kuilt-core` is logger-free, so
 * [onFailure] is how a host surfaces a per-link rejection/timeout to its own logger.
 *
 * @return the pump [Job] (a child of the receiver scope); cancel it to stop accepting.
 */
public fun CoroutineScope.acceptPump(
    source: ConnectionSource,
    handshakeTimeout: Duration,
    onFailure: (Throwable) -> Unit = {},
    handle: suspend (Connection) -> Unit,
): Job = launch {
    while (isActive) {
        val conn = source.accept()
        launch {
            val completed = withTimeoutOrNull(handshakeTimeout) {
                runCatchingCancellable { handle(conn) }
                    .onFailure { failure ->
                        onFailure(failure)
                        runCatchingCancellable { conn.close() }
                    }
                true
            }
            if (completed == null) {   // handshake exceeded the timeout — abandon + close
                onFailure(HandshakeTimeoutException(handshakeTimeout))
                runCatchingCancellable { conn.close() }
            }
        }
    }
}

/** Raised through [acceptPump]'s `onFailure` when a conn's [handle] does not finish within the timeout. */
public class HandshakeTimeoutException(timeout: Duration) :
    Exception("accept-pump: handshake did not complete within $timeout")
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :kuilt-core:jvmTest --tests "*AcceptPumpTest*"` → PASS.

- [ ] **Step 5: Migrate `MuxServerLoom.acceptLoop`** — replace the sequential loop with the pump. Read the current `acceptLoop`/`admit` (`MuxServerLoom.kt` ~186-217) first. **`kuilt-core` is logger-free** (verified: no logging dep in `kuilt-core/build.gradle.kts`; today's `acceptLoop` absorbs with a comment, no log) — so **do NOT add `logger.debug`**. Behaviour-parity is `onFailure = {}`; if observability is wanted, add an `internal` injectable `onLinkFailure: (Throwable) -> Unit = {}` constructor param and route it there.

```kotlin
// in MuxServerLoom, where acceptLoop() was launched:
scope.acceptPump(
    source = source,
    handshakeTimeout = handshakeTimeout,   // add a constructor param, default e.g. 10.seconds
    onFailure = {},                        // logger-free: parity with today's silent absorb
    handle = { conn -> admit(conn) },
)
```
Delete the old `private suspend fun acceptLoop()`. **Concurrent `admit` is SAFE** (Fable-verified, de-risked from the earlier draft): all shared state is lock-guarded (`connRecords`/`_connectedPeers` ~207-210, `launchedJobs` ~214-217), same-peer concurrency is handled by the identity-checked teardown (`connRecords[peerId] === record`, ~262 — the exact reconnect mechanism its KDoc documents), and room registration is lazy-per-first-frame so accept-order isn't load-bearing. Confirm the existing tests still pass: `MuxServerLoomLifecycleTest` (in **`:kuilt-core`** commonTest) and `MuxServerLoomFanoutIsolationTest` (in **`:kuilt-conformance`** commonTest — different module; run `./gradlew :kuilt-conformance:jvmTest --tests "*MuxServerLoomFanout*"`).

- [ ] **Step 6: Migrate `hostedOverlay.hostedMesh`** — replace `launch { while (isActive) { … addLink(source.accept()) … } }` with:

```kotlin
acceptPump(
    source = source,
    handshakeTimeout = handshakeTimeout,   // thread a param through hostedMesh/hostedOverlay
    onFailure = { e -> /* existing debug-log of LinkRejectedException */ },
    handle = { conn -> hubMesh.addLink(conn) },
)
```
Confirm `HostedOverlayTest` stays green.

- [ ] **Step 7: Full gate** — `./gradlew build detektAll --rerun-tasks` → EXECUTED, green (full build — touches `:kuilt-core`, `:kuilt-gossip`, and their downstream E2E).
- [ ] **Step 8: Commit / PR** — `feat(core): acceptPump — concurrent, handshake-timed accept loop; migrate hostedOverlay + MuxServerLoom`. `Closes` the (c)-generalization follow-up issue; part of #1450.

---

### Task 3: WebSocket ping/pong (symmetric half-open detection)

**Files:**
- Modify: server accept path — where the Ktor `WebSockets` plugin is installed for hosted routes (`kuilt-websocket/.../KtorServerLoom.kt` / `KtorRoomHost` / `KtorConnectionSource`). Set `pingPeriod`.
- Modify/document: client dial path — the `HttpClient` passed to `voterMeshOverWebSockets` must install `WebSockets { pingInterval = … }`. Add a documented requirement + (if a helper builds the client) set it there.
- Test: `kuilt-websocket/src/jvmTest/kotlin/.../WebSocketPingHalfOpenTest.kt` (real-socket integration).

**Interfaces:**
- Produces: a configured ping period on both ends so a half-open link's `readLoop` terminates within a bounded time → `removePeer` fires symmetrically. No new public API required (config only); if a `pingPeriod` param is added it defaults to a sane value (e.g. `15.seconds`).

- [ ] **Step 1: Write the failing test** — stand up a `KtorServerLoom` host + a client `KtorMeshClientLoom` spoke over a real loopback socket; sever the TCP connection **without** a close frame (simulate half-open — e.g. drop the underlying socket / stop reading without closing); assert the host side's `seam.peers` loses the peer within `pingPeriod + slack`. Confirm it FAILS today (no ping → peer lingers past the assertion window). (Model the transport-death injection on `WebSocketConformanceTest.injectMidSessionDeath`, but as a half-open rather than a clean server stop.)

- [ ] **Step 2:** Verify FAIL — the peer is NOT dropped within the window without pings.
- [ ] **Step 3: Configure `pingPeriod`** on the server `WebSockets` install (the hosted route) and `pingInterval` on the client `WebSockets` install; document the client requirement on `voterMeshOverWebSockets`'s `httpClient` param KDoc.
- [ ] **Step 4:** Verify PASS — half-open peer drops within the window on both ends.
- [ ] **Step 5: Full gate** — `./gradlew build detektAll --rerun-tasks`.
- [ ] **Step 6: Commit / PR** — `fix(websocket): ping/pong for bounded symmetric half-open detection`. Part of #1450.

> **Implementer notes (Fable-verified against Ktor 3.4.3 — the resolved version):**
> - Names are correct: server `pingPeriod: Duration?` (`io.ktor.server.websocket`), client `pingInterval: Duration?` (`io.ktor.client.plugins.websocket`).
> - **Pre-installed-plugin trap:** `KtorConnectionSource.kt:51` (and `KtorServerLoom.kt:103-104`) install `WebSockets` **only if absent** (`if (application.pluginOrNull(WebSockets) == null)`). If the host app pre-installed the plugin, kuilt's `pingPeriod` **silently never applies**. The task must either fail-loud/warn when a pre-installed plugin lacks a ping period, or document it prominently.
> - **Client engine matters:** the Ktor client ping is **ignored by the OkHttp engine** (OkHttp has its own `pingInterval`); the catalog ships both `ktor-client-okhttp` and `ktor-client-cio` (`gradle/libs.versions.toml:57-58`). Name the engine constraint in `voterMeshOverWebSockets`'s `httpClient` KDoc (require CIO, or set OkHttp's own ping).
> - **Test harness:** the half-open test **cannot** use `testApplication`/test-host (no real TCP to sever) — use `embeddedServer` + a real client engine, and sever the socket without a close frame.

---

### Task 4: `VoterReconnectionSupervisor` (kuilt-cluster, transport-agnostic)

**Files:**
- Create: `kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/VoterReconnectionSupervisor.kt` (internal)
- Test: `kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/VoterReconnectionSupervisorTest.kt`

**Interfaces:**
- Consumes: `ExponentialBackoff` (Task 1); `Mesh` (`mesh.peers: StateFlow<Set<PeerId>>`, `mesh.addLink(Connection)`); `Connection`, `PeerId` (kuilt-core).
- **`Mesh` = `Seam` + `PrincipalRoster` — a `FakeMesh` stub must implement all 9 members** (enumerate up front; don't discover `PrincipalRoster` mid-task): `selfId`, `peers`, `state`, `incoming`, `broadcast`, `sendTo`, `close(reason)`, `attestedPrincipals`, `addLink` (`plies` has a default). Only `peers` + `addLink` carry test behaviour; the rest are no-op/throw stubs.
- **Task 3 (WS pings) is a HARD prerequisite for real-world correctness, not just symmetric detection:** the supervisor is blind to a *local* half-open corpse — if the dialer's own mesh still holds the dead link, `peer in peers` stays `true` and no redial fires until the local ping reaps it. Task 5's integration test only passes with Task 3 landed (wall-clock bound ≥ `pingPeriod + backoff`).
- Produces:
  ```kotlin
  internal fun CoroutineScope.superviseVoterReconnection(
      mesh: Mesh,
      dialTargets: Set<PeerId>,                     // the peers THIS voter is the designated dialer for
      dial: suspend (PeerId) -> Connection,          // transport-specific; injected
      backoff: ExponentialBackoff,
      onDialFailure: (PeerId, Throwable) -> Unit = { _, _ -> },  // observability of an infinite retry loop
  ): Job
  ```
  Launches one child coroutine per `dialTargets` peer. Each watches `mesh.peers.map { p in it }.distinctUntilChanged()` and, whenever the peer is absent, re-dials under `backoff` until it returns (`collectLatest` cancels the redial the instant the peer reappears). Runs forever; cancel the returned `Job` (or its parent scope) to stop.

- [ ] **Step 1: Write the failing test — a dropped peer is re-dialed and the redial stops on return**

```kotlin
package us.tractat.kuilt.cluster

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.atomicfu.atomic
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VoterReconnectionSupervisorTest {

    // Fake Mesh: exposes a controllable peers flow and records dial→addLink; addLink republishes the peer.
    private class FakeMesh(self: PeerId, initial: Set<PeerId>) : Mesh {
        val peersFlow = MutableStateFlow(initial + self)
        val addLinkCount = atomic(0)
        override val peers get() = peersFlow
        override suspend fun addLink(conn: Connection) {
            addLinkCount.incrementAndGet()
            peersFlow.value = peersFlow.value + (conn as FakeConn).forPeer   // link admitted → peer back
        }
        /* remaining Mesh/Seam members: minimal no-op/throw stubs */
    }

    @Test
    fun redialsADroppedPeerAndStopsWhenItReturns() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val self = PeerId("v0"); val p = PeerId("v1")
        val mesh = FakeMesh(self, initial = setOf(p))                 // starts connected
        val backoff = ExponentialBackoff(50.milliseconds, 5.seconds, random = Random(1))
        val job = superviseVoterReconnection(
            mesh = mesh, dialTargets = setOf(p),
            dial = { peer -> FakeConn(forPeer = peer) }, backoff = backoff,
        )
        runCurrent()
        assertEquals(0, mesh.addLinkCount.value, "no redial while the peer is present")

        mesh.peersFlow.value = setOf(self)          // p drops
        advanceTimeBy(1.seconds); runCurrent()      // bounded — past a couple of backoff cycles
        assertTrue(mesh.addLinkCount.value >= 1, "a dropped peer is re-dialed")
        assertTrue(mesh.peersFlow.value.contains(p), "the redial's addLink brought the peer back")

        val countAfterHeal = mesh.addLinkCount.value
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(countAfterHeal, mesh.addLinkCount.value, "no further redials once the peer is back")
        job.cancel()
    }
}
```
(Define minimal `FakeConn(val forPeer: PeerId)` + the `FakeMesh` stub members. A second test should cover **lost-to-corpse**: `addLink` that does NOT republish the peer → the loop keeps retrying under growing backoff; assert `addLinkCount` grows across bounded advances. A third: `dialTargets` this voter does NOT own are never dialed.)

- [ ] **Step 2:** Verify FAIL — unresolved `superviseVoterReconnection`.

- [ ] **Step 3: Implement**

```kotlin
package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.util.ExponentialBackoff

/**
 * Keep [mesh]'s links to [dialTargets] alive forever by re-dialing any that drop. One child coroutine
 * per target watches "is this peer present?" and, while absent, re-dials under [backoff]; [collectLatest]
 * cancels the redial the instant the peer returns. Only the peers this voter is the *designated dialer*
 * for are in [dialTargets] (the lower-id-dials-higher rule), so no pair is ever double-dialed.
 *
 * Start this AFTER formation, when every [dialTargets] peer is already present — the loops then sit idle
 * until a real drop. Launch on the mesh's own lifecycle scope so it is cancelled with the voter.
 */
internal fun CoroutineScope.superviseVoterReconnection(
    mesh: Mesh,
    dialTargets: Set<PeerId>,
    dial: suspend (PeerId) -> Connection,
    backoff: ExponentialBackoff,
    onDialFailure: (PeerId, Throwable) -> Unit = { _, _ -> },
): Job = launch {
    for (peer in dialTargets) {
        launch {
            mesh.peers
                .map { peer in it }
                .distinctUntilChanged()
                .collectLatest { present ->
                    if (!present) {
                        var attempt = 0
                        while (true) {
                            // Guard: full jitter's lower bound is ~0ms, so on a multi-threaded dispatcher
                            // the redial below could fire once more before collectLatest processes the
                            // post-addLink `true` emission. Cheap re-check avoids that redundant dial.
                            // (The nonce dedup would absorb it as waste, not corruption — this is tidiness.)
                            if (peer in mesh.peers.value) break
                            // addLink dials+handshakes+admits. If it wins, `peer` reappears and
                            // collectLatest cancels us. If it throws or loses dedup to a not-yet-reaped
                            // corpse, back off and retry. runCatchingCancellable keeps cancellation clean.
                            runCatchingCancellable { mesh.addLink(dial(peer)) }
                                .onFailure { onDialFailure(peer, it) }
                            delay(backoff.delay(attempt++))
                        }
                    }
                    // present == true → stay suspended here until `peer` leaves again.
                }
        }
    }
}
```

- [ ] **Step 4:** Verify PASS (all three tests).
- [ ] **Step 5: Full gate** — `./gradlew :kuilt-cluster:build detektAll --rerun-tasks` (+ `:examples:test` given it's consensus-adjacent runtime).
- [ ] **Step 6: Commit / PR** — `feat(cluster): VoterReconnectionSupervisor — per-peer redial loops`. Part of #1450.

---

### Task 5: Wire into `voterMeshOverWebSockets` (persistent accept-pump + supervisor; synchronous formation preserved)

**Files:**
- Modify: `kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/VoterSeamMesh.kt` — `voterMeshOverSeams` currently **creates `meshScope` internally** (line ~57: `val meshScope = CoroutineScope(coroutineContext + Job(...))`) and only after formation. Add an `internal` overload / optional param so the caller can **supply a pre-built `meshScope`** (defaulting to today's construction for existing callers).
- Modify: `kuilt-cluster/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/cluster/WebSocketVoterMesh.kt` (formation block ~101-119; add reconnection params + build the scope up front)
- Test: `kuilt-cluster/src/jvmTest/kotlin/.../WebSocketVoterMeshReconnectionTest.kt` (real-socket integration)

**Interfaces:**
- Consumes: `acceptPump` (Task 2), `superviseVoterReconnection` (Task 4), `ExponentialBackoff` (Task 1), the WS ping config (Task 3).
- Produces: `voterMeshOverWebSockets` that (a) **builds the mesh lifecycle scope up front** and runs a **persistent** `acceptPump` per voter on it from t0 (replacing the bounded `repeat(index)` accept), (b) awaits the full roster **under a formation timeout** before returning (synchronous formation → RaftNodes start fully-linked), (c) starts the per-voter supervisor on that same scope, and (d) hands the scope to `voterMeshOverSeams` so `VoterMesh.close()` cancels pumps + supervisors + nodes together.
- **THE SCOPE BLOCKER (Fable finding #1, verified):** the pumps must run from t0 (before/during formation), but `VoterMesh.scope`/`meshScope` doesn't exist until `voterMeshOverSeams` returns *after* formation. Launching on the receiver scope instead would leak (a closed `VoterMesh` cancels only `meshScope`, and the seams are deliberately *not* closed — `VoterSeamMesh.kt:40-41, VoterMesh.kt:73-75`); launching inside the formation `coroutineScope` never returns (the pump is infinite). **Resolution: create `meshScope` in `voterMeshOverWebSockets` first, launch everything on it, and pass it into `voterMeshOverSeams`.**

- [ ] **Step 1: Write the failing integration test** — form a 3-voter mesh over real loopback WebSockets (`embeddedServer` + a CIO client — NOT `testApplication`, see Task 3); once a leader is elected and a command commits, **drop one edge** (sever a voter-to-voter socket half-open — no close frame); assert (a) the peer returns to both ends' `seam.peers` within a bounded wall-clock timeout (≥ `pingPeriod + backoff`), and (b) a command proposed *after* the heal commits on all three. Add an **M=2** variant. Add a **lifecycle** assertion: after `voterMesh.close()`, no further dials/accepts occur (Fable finding #5 — the scope must actually cancel the pumps/supervisors). Real-socket test → carry an inline `@Suppress` + one-line reason for the real dispatcher.

- [ ] **Step 2:** Verify FAIL against current `main` — dropped edge never returns (no reconnection).

- [ ] **Step 3: Add the pre-built-scope param to `voterMeshOverSeams`** (`VoterSeamMesh.kt`) so the caller owns the mesh scope:

```kotlin
// internal overload (or optional param) — existing public signature unchanged, defaults to today's behaviour:
internal fun voterMeshOverSeams(
    voterSeams: Map<NodeId, Seam>,
    raftConfig: RaftConfig,
    meshScope: CoroutineScope,                       // NEW: caller-supplied lifecycle scope
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
): VoterMesh { /* build voterNodes as child scopes of meshScope; return VoterMesh(voterNodes, meshScope) */ }
// The existing `CoroutineScope.voterMeshOverSeams(...)` delegates, creating meshScope as it does today.
```

- [ ] **Step 4: Build the scope up front + persistent pump + synchronous formation under a timeout** in `voterMeshOverWebSockets`:

```kotlin
val meshScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
val fullPeerIdSet: Set<PeerId> = ordered.map { PeerId(it.nodeId.value) }.toSet()   // includes self — Seam.peers includes selfId

// (a) persistent accept-pump per voter, from t0, on meshScope (replaces the bounded repeat(index) accept):
ordered.forEach { voter ->
    meshScope.acceptPump(
        source = voter.source, handshakeTimeout = handshakeTimeout, onFailure = {},
        handle = { conn -> meshes.getValue(voter.nodeId).addLink(conn) },
    )
}
// (b) initial dials + await the full roster, under a formation timeout (guards a stalled handshake / crashed voter):
withTimeout(formationTimeout) {
    coroutineScope {
        ordered.forEachIndexed { index, voter ->
            val mesh = meshes.getValue(voter.nodeId)
            launch { ordered.drop(index + 1).forEach { higher -> mesh.addLink(dial(voter, higher)) } }  // dial higher
            launch { mesh.peers.first { it.containsAll(fullPeerIdSet) } }                                // await roster
        }
    }
}
```
(`containsAll` over `==` is robust to a stray non-voter conn on the route. A TCP-level dial failure throws → `coroutineScope` aborts fast; the `withTimeout` bounds a *stalled* handshake or a crashed voter.)

- [ ] **Step 5: Start the supervisor per voter on `meshScope`, then build the mesh with that scope:**

```kotlin
ordered.forEachIndexed { index, voter ->
    val higher = ordered.drop(index + 1).map { PeerId(it.nodeId.value) }.toSet()
    meshScope.superviseVoterReconnection(
        mesh = meshes.getValue(voter.nodeId),
        dialTargets = higher,
        dial = { peer -> WebSocketConnection(httpClient.webSocketSession(dialUrlByPeer.getValue(peer))) },
        backoff = ExponentialBackoff(base = 200.milliseconds, cap = 30.seconds, random = voterRandom.getValue(voter.nodeId)),
    )
}
return meshScope.let { /* pass it in */ } .run {
    voterMeshOverSeams(voterSeams = meshes, raftConfig = raftConfig, meshScope = meshScope, storageFactory = storageFactory)
}
```
(Build `dialUrlByPeer: Map<PeerId, String>` from the `WebSocketVoter` list. Reuse the existing per-voter seeded `voterRandom` child so nothing is shared across concurrent loops. `VoterMesh.close()` now cancels `meshScope` → pumps + supervisors + nodes all stop together.)

- [ ] **Step 6:** Verify PASS — 3-voter heal + post-heal commit; M=2 survive-a-blip; **close() stops all pumps/supervisors**.
- [ ] **Step 7: Full gate** — `./gradlew build detektAll --rerun-tasks` (full build + E2E).
- [ ] **Step 8: Commit / PR** — `feat(cluster): voter mesh reconnects dropped links (closes #1450)`. `Closes #1450`.

---

## Self-Review notes
- **Spec coverage:** hubMesh-forever (unchanged — no task needed) ✓; per-peer redial loops (Task 4) ✓; shared accept-pump + migrations (Task 2) ✓; ExponentialBackoff full jitter (Task 1) ✓; synchronous formation + wiring + VoterMesh.scope (Task 5) ✓; #1452 conn-guard (merged, prerequisite) ✓; WS ping/pong (Task 3) ✓.
- **Ordering:** Tasks 1–4 are independent; Task 5 depends on 1+2+4 (and benefits from 3). Land 1, 2, 3, 4 (any order), then 5.
- **Reconciled with the Fable plan review (2026-07-15, findings verified against source):**
  - Task 5 scope blocker — RESOLVED: `meshScope` built up front in `voterMeshOverWebSockets`, passed into a new `internal voterMeshOverSeams(..., meshScope)` overload (Steps 3–5). This was the one non-buildable item.
  - Task 2 logger-in-kuilt-core — REMOVED (`onFailure = {}`); dead `LinkRejectedException` branch dropped; concurrent `admit` confirmed SAFE (lock-guarded + identity-checked teardown), so the "highest-risk" label was wrong.
  - Compile idioms fixed everywhere: `runTest(StandardTestDispatcher(), timeout = 5.seconds)`, `us.tractat.kuilt.test.assertAll`.
  - Task 4: added the `if (peer in mesh.peers.value) break` near-zero-jitter guard + `onDialFailure`; enumerated the 9 `Mesh` members; Task 3 marked a HARD prerequisite (local-corpse blindness).
  - Task 3: pre-installed-plugin trap (`KtorConnectionSource.kt:51`), OkHttp-ignores-ping engine caveat, `embeddedServer` (not `testApplication`) all noted.
  - Task 5: formation `withTimeout`, `fullPeerIdSet` defined (incl. self, `containsAll`), and a `close()` lifecycle assertion added.
  - Core design (level-triggered `collectLatest` over synchronously-publishing `StateFlow`, hub-mesh-forever, nonce dedup) verified sound against real `MeshSeam` semantics — no change.
