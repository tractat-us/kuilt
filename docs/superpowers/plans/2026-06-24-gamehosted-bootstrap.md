# `gameHosted` Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the public, transport-agnostic front door that lets a server accept inbound connections and run a hosted star game over them — `gameHosted(selfId, KtorConnectionSource(app, path), peerCount)` → live `GameSession` — without cloning kuilt's `internal` WebSocket adapter.

**Architecture:** Three layers, dependency arrow always pointing down. A new `ConnectionSource` contract in `:kuilt-core` exposes a `suspend fun accept(): Connection`. `:kuilt-gossip` adds `hostedOverlay(...)` — the production graduation of the test-only `inMemoryStarOf` body — that pumps accepted connections into an empty `Mesh` wrapped in a `GossipSeam(FullFanout)`. `:kuilt-websocket` implements `KtorConnectionSource` reusing the existing `internal WebSocketConnection`. `:kuilt-game` adds `gameHosted = gameHost(hostedOverlay(...))`. `:kuilt-test` adds `InMemoryConnectionSource` and `inMemoryStarOf` is re-expressed on top of `hostedOverlay` so there is one composition path, not two.

**Tech Stack:** Kotlin Multiplatform (JVM/Android/iOS/macOS/wasmJs), kotlinx-coroutines, Ktor WebSockets (JVM/Android only). `runTest` + `StandardTestDispatcher` for virtual-time tests.

## Global Constraints

- **`explicitApi()` is enforced** — every new public declaration gets an explicit `public` modifier or the build fails.
- **Dependency direction never points back into `:kuilt-core`.** `ConnectionSource` is a `:kuilt-core` contract; implementations live downstream.
- **Injected dispatcher + seeded RNG.** Any new scope-owning type takes its dispatcher/scope as a parameter (no real-dispatcher default); any randomness is a seeded `Random` parameter. No `Dispatchers.{Unconfined,Default,IO,Main}` / `GlobalScope` in test sources.
- **No `limitedParallelism(1)` confinement-as-mutex.** New scope-owning types must be correct under a multi-threaded dispatcher; guard shared mutable state with atomicfu locks/atomics or thread-safe structures (`Channel`, `MutableStateFlow`). `Channel(UNLIMITED)` is the right primitive here.
- **Exception discipline** — use `runCatchingCancellable` (in `:kuilt-core`), never bare `runCatching`; a `catch (Exception)` that tolerates failure must rethrow `CancellationException` first.
- **Virtual-time tests** use `runTest(StandardTestDispatcher())` with bounded advance (`runCurrent()` / `advanceTimeBy`), never `advanceUntilIdle()` on a system with re-arming timers. Node/pump coroutines live on `backgroundScope` so they cancel cleanly at teardown.
- **Verification before each PR leaves draft:** `./gradlew build` (full, all targets) **and** `./gradlew detektAll` (not bare `detekt`) green locally.
- **PR references:** every impl PR body says `Part of #794` (non-closing for the epic). The closing keyword (`Closes #831`) belongs only on the spec+plan docs PR.

---

## File Structure

| Module | File | Responsibility |
|--------|------|----------------|
| `:kuilt-core` | `src/commonMain/kotlin/us/tractat/kuilt/core/fabric/ConnectionSource.kt` (create) | The `ConnectionSource` accept contract. |
| `:kuilt-test` | `src/commonMain/kotlin/us/tractat/kuilt/test/fabric/InMemoryConnectionSource.kt` (create) | In-memory `ConnectionSource` backed by an unlimited `Channel<Connection>`; `offer(conn)` pushes a hub-end. |
| `:kuilt-gossip` | `src/commonMain/kotlin/us/tractat/kuilt/gossip/HostedOverlay.kt` (create) | `hostedOverlay(...)` — empty `Mesh` + `GossipSeam(FullFanout)` + accept-pump. |
| `:kuilt-test` | `src/commonMain/kotlin/us/tractat/kuilt/test/fabric/StarHarness.kt` (modify) | Re-express `inMemoryStarOf` on `hostedOverlay`; `Star.hubMesh: Mesh` → `Star.source: InMemoryConnectionSource`. |
| `:kuilt-gossip` | `src/commonTest/kotlin/us/tractat/kuilt/gossip/HostedOverlayTest.kt` (create) | Composition + late-join test for `hostedOverlay`. |
| `:kuilt-websocket` | `src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/websocket/KtorConnectionSource.kt` (create) | Public WS `ConnectionSource` reusing `internal WebSocketConnection`. |
| `:kuilt-websocket` | `src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorConnectionSourceRoundTripTest.kt` (create) | Real-Ktor round-trip: a client message reaches the accepted `Connection`. |
| `:kuilt-game` | `src/commonMain/kotlin/us/tractat/kuilt/game/GameHosted.kt` (create) | `gameHosted = gameHost(hostedOverlay(...))`; adds `:kuilt-gossip` main dep. |
| `:kuilt-game` | `src/commonTest/kotlin/us/tractat/kuilt/game/GameHostedLeakTest.kt` (create) | Loopback leak invariant: per-seat `sendTo` is never relayed. |
| `:kuilt-game` | `build.gradle.kts` (modify) | Add `implementation(project(":kuilt-gossip"))` to `commonMain`. |

**Grounded reference signatures (verbatim from the tree, do not re-derive):**

```kotlin
// kuilt-core
public value class PeerId(public val value: String)
public interface Connection {
    public suspend fun send(frame: ByteArray)
    public val incoming: Flow<ByteArray>
    public suspend fun close()
}
public interface Mesh : Seam { public suspend fun addLink(conn: Connection) }
public suspend fun meshSeam(
    selfId: PeerId, connections: List<Connection>, dispatcher: CoroutineContext,
    random: Random = Random.Default, policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Mesh
// Seam (subset): val selfId, val peers: StateFlow<Set<PeerId>>, val incoming: Flow<Swatch>,
//   suspend fun broadcast(payload: ByteArray), suspend fun sendTo(peer: PeerId, payload: ByteArray)

// kuilt-gossip
public class GossipSeam(
    base: Seam, random: Random, clock: () -> Instant,
    config: HeartbeatConfig = HeartbeatConfig(), spareCount: Int = …, jitter: ClosedRange<Duration> = …,
    initialTtl: Int = DEFAULT_TTL, activeViewPolicy: ActiveViewPolicy = ActiveViewPolicy.RandomKRegular,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Seam
public val ActiveViewPolicy.Companion ... // ActiveViewPolicy.FullFanout is a public val
// GossipSeam.start(scope: CoroutineScope); GossipSeam.sendTo passes through to base unwrapped.

// kuilt-test
public fun connectionPair(policy: DeliveryPolicy = DeliveryPolicy.Reliable): Pair<Connection, Connection>

// kuilt-websocket (internal, reused — stays internal)
internal class WebSocketConnection(session: DefaultWebSocketSession) : Connection

// kuilt-game
public suspend fun CoroutineScope.gameHost(
    seam: Seam, peerCount: Int, returnAt: ReturnPolicy = ReturnPolicy.FullMembership,
    allowSpectators: Boolean = false, maxSpectators: Int = 0,
    storage: RaftStorage = InMemoryRaftStorage(), raftConfig: RaftConfig = RaftConfig(),
    livenessConfig: HeartbeatConfig? = null, clock: () -> Instant = { Clock.System.now() },
    hostDeclarationTimeout: Duration = DEFAULT_HOST_DECLARATION_TIMEOUT,
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession
```

---

## Task A (PR-A): `ConnectionSource` + `InMemoryConnectionSource` + `hostedOverlay`, with `inMemoryStarOf` re-expressed on top

This is the structural unblock. It proves the composer end-to-end in-memory under virtual time and collapses the two star-composition paths into one.

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/ConnectionSource.kt`
- Create: `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/InMemoryConnectionSource.kt`
- Create: `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/HostedOverlay.kt`
- Modify: `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/StarHarness.kt`
- Create: `kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/HostedOverlayTest.kt`
- Modify: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HostedHubReplicationTest.kt` (mechanical: reconnect path uses `source.offer` not `hubMesh.addLink`)

**Interfaces:**
- Consumes: `Connection`, `Mesh`, `meshSeam`, `Seam` (kuilt-core); `GossipSeam`, `ActiveViewPolicy.FullFanout` (kuilt-gossip); `connectionPair` (kuilt-test).
- Produces:
  - `public interface ConnectionSource { public suspend fun accept(): Connection }`
  - `public class InMemoryConnectionSource : ConnectionSource { public fun offer(connection: Connection); override suspend fun accept(): Connection }`
  - `public suspend fun CoroutineScope.hostedOverlay(selfId: PeerId, source: ConnectionSource, dispatcher: CoroutineContext, random: Random = Random(0L), clock: () -> Instant = { Clock.System.now() }): Seam`
  - `Star` changes: `public val source: InMemoryConnectionSource` replaces `public val hubMesh: Mesh`.

- [ ] **Step 1: Write the `ConnectionSource` contract**

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/ConnectionSource.kt
package us.tractat.kuilt.core.fabric

/**
 * A transport-agnostic stream of inbound peer [Connection]s — the "front door" of a hosted
 * session. Unlike `KtorServerLoom.nextLink()`, which collapses each accepted session into a
 * finished 2-peer [us.tractat.kuilt.core.Seam], [accept] yields the raw [Connection] (a hub
 * spoke) so a composer can bond many of them into one group view.
 */
public interface ConnectionSource {
    /** Suspends until the next inbound peer connection is accepted, then returns it. */
    public suspend fun accept(): Connection
}
```

- [ ] **Step 2: Write the failing `hostedOverlay` test (composition + late join)**

```kotlin
// kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/HostedOverlayTest.kt
package us.tractat.kuilt.gossip

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class HostedOverlayTest {

    /** A connection offered AFTER the hub has started still joins — the accept-pump admits it. */
    @Test
    fun lateJoinedConnectionJoinsRunningHub() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
        val source = InMemoryConnectionSource()

        // Start the hub with no connections yet.
        val hub = backgroundScope.hostedOverlay(PeerId("hub"), source, dispatcher, Random(0L), clock)

        // A client connects after the hub is already running.
        val (hubEnd, clientEnd) = connectionPair()
        val clientBuild = backgroundScope.async {
            GossipSeam(meshSeam(PeerId("client-0"), listOf(clientEnd), dispatcher), Random(1L), clock)
                .also { it.start(backgroundScope) }
        }
        source.offer(hubEnd)            // pump accepts → addLink on the running hub
        val client = clientBuild.await()

        // Both sides see each other once the handshake crosses.
        hub.peers.first { PeerId("client-0") in it }
        client.peers.first { PeerId("hub") in it }
        assertTrue(PeerId("client-0") in hub.peers.value)
    }
}
```

- [ ] **Step 3: Run it — expect compile failure** (`hostedOverlay` / `InMemoryConnectionSource` unresolved)

Run: `./gradlew :kuilt-gossip:compileTestKotlinJvm`
Expected: FAIL — unresolved reference `hostedOverlay`, `InMemoryConnectionSource`.

- [ ] **Step 4: Implement `InMemoryConnectionSource`**

```kotlin
// kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/InMemoryConnectionSource.kt
package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.channels.Channel
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource

/**
 * In-memory [ConnectionSource]: [offer] a hub-end [Connection] (typically from [connectionPair]);
 * [accept] receives it. The same accept path a real server uses, minus the wire — drives a hosted
 * overlay end-to-end under virtual time.
 */
public class InMemoryConnectionSource : ConnectionSource {
    private val channel = Channel<Connection>(capacity = Channel.UNLIMITED)
    public fun offer(connection: Connection) { channel.trySend(connection) }
    override suspend fun accept(): Connection = channel.receive()
}
```

- [ ] **Step 5: Implement `hostedOverlay`** (graduates the `inMemoryStarOf` body)

```kotlin
// kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/HostedOverlay.kt
package us.tractat.kuilt.gossip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.meshSeam
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Compose a started hub [Seam] from a [ConnectionSource]: an initially-empty [meshSeam] wrapped in
 * a [GossipSeam] with [ActiveViewPolicy.FullFanout] (the hub floods every broadcast to all spokes),
 * plus an accept-pump that [addLink]s each accepted [Connection] so clients join the running hub as
 * they connect. The pump coroutine lives on the receiver scope and is torn down with it.
 *
 * This is the production form of the in-memory star the test harness composes by hand; the harness
 * is re-expressed on top of it so there is one composition path.
 */
public suspend fun CoroutineScope.hostedOverlay(
    selfId: PeerId,
    source: ConnectionSource,
    dispatcher: CoroutineContext,
    random: Random = Random(0L),
    clock: () -> Instant = { Clock.System.now() },
): Seam {
    val hubMesh = meshSeam(selfId = selfId, connections = emptyList(), dispatcher = dispatcher)
    val hub = GossipSeam(
        base = hubMesh,
        random = random,
        clock = clock,
        activeViewPolicy = ActiveViewPolicy.FullFanout,
    ).also { it.start(this) }
    launch { while (isActive) hubMesh.addLink(source.accept()) }
    return hub
}
```

- [ ] **Step 6: Run the test — expect PASS**

Run: `./gradlew :kuilt-gossip:jvmTest --tests "*HostedOverlayTest"`
Expected: PASS.

- [ ] **Step 7: Re-express `inMemoryStarOf` on `hostedOverlay`; change `Star.hubMesh` → `Star.source`**

Rewrite `StarHarness.kt` so the hub is composed by `hostedOverlay` over an `InMemoryConnectionSource` (clients stay hand-built and concurrent). The `Star` reconnect handle becomes the production-faithful `source`:

```kotlin
public class Star(
    public val hub: Seam,
    public val clients: List<GossipSeam>,
    public val source: InMemoryConnectionSource,  // admit a fresh client via source.offer(hubEnd)
)

public suspend fun CoroutineScope.inMemoryStarOf(
    n: Int,
    hubId: PeerId = PeerId("hub"),
    random: Random = Random(0L),
): Star {
    val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
    val source = InMemoryConnectionSource()

    val clientConnections = ArrayList<Pair<PeerId, Connection>>(n)
    for (i in 0 until n) {
        val (hubEnd, clientEnd) = connectionPair()
        source.offer(hubEnd)
        clientConnections += PeerId("client-$i") to clientEnd
    }

    // Hub pump and all client handshakes must run concurrently — Hello preambles cross in parallel.
    val (hub, clients) = coroutineScope {
        val hubDeferred = async { hostedOverlay(hubId, source, dispatcher, Random(random.nextLong()), clock) }
        val clientDeferreds = clientConnections.map { (id, conn) ->
            async {
                GossipSeam(meshSeam(id, listOf(conn), dispatcher), Random(random.nextLong()), clock)
                    .also { it.start(this@inMemoryStarOf) }
            }
        }
        hubDeferred.await() to clientDeferreds.awaitAll()
    }
    return Star(hub, clients, source)
}
```

Then fix the only `.hubMesh` consumer — `HostedHubReplicationTest.kt` (~line 188, 220): replace `star.hubMesh.addLink(hubEnd)` with `star.source.offer(hubEnd)` and drop the now-unused `hubMesh` accessor. (Add `:kuilt-gossip` is already on `:kuilt-test`'s path — verify; `meshSeam` is `:kuilt-core`.)

- [ ] **Step 8: Full local verification**

Run: `./gradlew :kuilt-test:build :kuilt-gossip:build :kuilt-game:jvmTest detektAll`
Then the full gate: `./gradlew build`
Expected: BUILD SUCCESSFUL; `StarHarnessTest` and `HostedHubReplicationTest` still green (they now run through `hostedOverlay`).

- [ ] **Step 9: Commit & open PR-A**

```bash
git commit -am "feat(kuilt-core,gossip): ConnectionSource accept + hostedOverlay composer

Add the transport-agnostic accept contract (kuilt-core) and hostedOverlay
(kuilt-gossip) — the production form of inMemoryStarOf's body — plus
InMemoryConnectionSource (kuilt-test). Re-express inMemoryStarOf on the new
composer: one star-composition path, not two. Star.hubMesh -> Star.source so
reconnect tests admit via the real accept path.

Part of #794"
```
PR body: `Part of #794`, `Prev: #831 (spec+plan).` Auto-merge once `ci-required` green.

---

## Task B (PR-B): `KtorConnectionSource` — the real WebSocket front door

**Files:**
- Create: `kuilt-websocket/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/websocket/KtorConnectionSource.kt`
- Create: `kuilt-websocket/src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorConnectionSourceRoundTripTest.kt`

**Interfaces:**
- Consumes: `ConnectionSource`, `Connection` (kuilt-core); `internal WebSocketConnection` (same module, reused as-is).
- Produces: `public class KtorConnectionSource(application: Application, path: String, dispatcher: CoroutineDispatcher = Dispatchers.IO) : ConnectionSource` with `override suspend fun accept(): Connection`.

**Key design notes (the two gotchas — get these right):**
1. **Reuse `WebSocketConnection(this)` directly** inside the route handler — it is `internal` to `:kuilt-websocket`, so no implementation type crosses the public boundary; only `ConnectionSource` (a kuilt-core type) does. Mirror `KtorServerLoom`'s init structure (`installWebSocketsIfAbsent`, `application.routing { webSocket(path) { … } }`, an unlimited `Channel`) but send a `Connection`, not a `Seam`.
2. **The route handler must stay alive until the connection is torn**, or Ktor closes the session out from under the hub. `KtorServerLoom` holds the handler open with `seam.peers.first { clientPeerId !in it }`; for a raw `Connection` there is no seam, so suspend on the session lifetime instead — `this.closeReason.await()` (a `DefaultWebSocketSession.closeReason: Deferred<CloseReason?>`). **Do not read `session.incoming` in the handler** — `WebSocketConnection.incoming` is single-collection and the consuming `Mesh` owns it; reading it here would steal frames.

- [ ] **Step 1: Write the failing round-trip test** (real Ktor `testApplication`)

```kotlin
// kuilt-websocket/src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorConnectionSourceRoundTripTest.kt
package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class KtorConnectionSourceRoundTripTest {

    @Test
    fun acceptedConnectionReceivesClientFrame() = testApplication {
        lateinit var source: KtorConnectionSource
        application { source = KtorConnectionSource(this, "/hub") }
        val client = createClient { install(ClientWebSockets) }

        val accepted = async { source.accept() }                 // server side: front door
        val payload = byteArrayOf(1, 2, 3, 4)
        client.webSocket("/hub") {
            send(Frame.Binary(fin = true, data = payload))
            val conn = accepted.await()
            assertContentEquals(payload, conn.incoming.first())   // raw Connection delivered the frame
        }
    }
}
```

- [ ] **Step 2: Run it — expect compile failure**

Run: `./gradlew :kuilt-websocket:compileTestKotlinJvm`
Expected: FAIL — unresolved reference `KtorConnectionSource`.

- [ ] **Step 3: Implement `KtorConnectionSource`** (mirror `KtorServerLoom`'s structure)

```kotlin
// kuilt-websocket/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/websocket/KtorConnectionSource.kt
package us.tractat.kuilt.websocket

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource

/**
 * Server-side [ConnectionSource] backed by Ktor WebSockets — the Connection-aggregation (hub)
 * counterpart of [KtorServerLoom] (which is the 2-peer/relay topology). Mounts a WebSocket route at
 * [path]; each accepted session is wrapped in the internal [WebSocketConnection] and emitted as a
 * raw [Connection] (a hub spoke), so a composer (`hostedOverlay`) can bond many into one hub.
 *
 * A WS session is *either* a relay seam ([KtorServerLoom]) *or* a hub spoke (this) — decided by
 * which accept object the server installs on the route.
 */
public class KtorConnectionSource(
    application: Application,
    path: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionSource {
    private val connections = Channel<Connection>(capacity = Channel.UNLIMITED)

    init {
        if (application.pluginOrNull(WebSockets) == null) application.install(WebSockets)
        application.routing {
            webSocket(path) {
                connections.send(WebSocketConnection(this))
                // Hold the handler open for the connection's lifetime; the consuming Mesh owns
                // `incoming`, so do NOT read session.incoming here.
                closeReason.await()
            }
        }
    }

    override suspend fun accept(): Connection = connections.receive()
}
```

> If `closeReason.await()` proves insufficient to hold the handler (e.g. it completes early in `testApplication`), suspend on a never-completing await tied to the session job instead — the requirement is only "do not let the `webSocket(path)` block return while the `Connection` is in use." Confirm via the round-trip test plus a teardown assertion.

- [ ] **Step 4: Run the test — expect PASS**

Run: `./gradlew :kuilt-websocket:jvmTest --tests "*KtorConnectionSourceRoundTripTest"`
Expected: PASS.

- [ ] **Step 5: Full verification & commit**

Run: `./gradlew :kuilt-websocket:build detektAll` then `./gradlew build`
```bash
git commit -am "feat(kuilt-websocket): KtorConnectionSource — WS front door for hosted hubs

Public ConnectionSource that accepts WebSocket sessions and yields each as a raw
Connection (hub spoke), reusing the internal WebSocketConnection adapter — the
Connection-aggregation counterpart to KtorServerLoom's relay seam.

Part of #794"
```
PR body: `Part of #794`, `Prev: #<PR-A>.` Auto-merge once green.

---

## Task C (PR-C): `gameHosted` + the loopback leak invariant test

**Files:**
- Modify: `kuilt-game/build.gradle.kts` — add `implementation(project(":kuilt-gossip"))` to `commonMain.dependencies`.
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameHosted.kt`
- Create: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameHostedLeakTest.kt`

**Interfaces:**
- Consumes: `hostedOverlay` (kuilt-gossip), `gameHost` (kuilt-game), `ConnectionSource` (kuilt-core), `InMemoryConnectionSource` + `connectionPair` (kuilt-test).
- Produces: `public suspend fun CoroutineScope.gameHosted(selfId: PeerId, source: ConnectionSource, peerCount: Int, …gameHost params…): GameSession`.

- [ ] **Step 1: Add the `:kuilt-gossip` main dependency**

In `kuilt-game/build.gradle.kts`, `commonMain.dependencies { … }`, add after the existing project deps:
```kotlin
implementation(project(":kuilt-gossip"))
```
(Today `:kuilt-gossip` is on `:kuilt-game`'s path only transitively via tests; `gameHosted` makes it a real main edge — see the spec's piece 4.)

- [ ] **Step 2: Write the failing leak-invariant test**

```kotlin
// kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameHostedLeakTest.kt
package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GameHostedLeakTest {

    /** A per-seat `sendTo(client-0, …)` is never relayed by FullFanout to client-1. */
    @Test
    fun perSeatSendToIsNeverRelayed() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
        val source = InMemoryConnectionSource()

        // Build the hub overlay + two clients (use hostedOverlay directly — gameHost not needed to
        // pin the wire-level invariant).
        val clientEnds = (0..1).map { i ->
            val (hubEnd, clientEnd) = connectionPair()
            source.offer(hubEnd)
            PeerId("client-$i") to clientEnd
        }
        val hub = backgroundScope.hostedOverlayForTest(PeerId("hub"), source, dispatcher, clock)
        val clients = clientEnds.map { (id, conn) ->
            id to GossipSeam(meshSeam(id, listOf(conn), dispatcher), Random(id.value.hashCode().toLong()), clock)
                .also { it.start(backgroundScope) }
        }
        hub.peers.first { clients.all { (id, _) -> id in it } }   // converged

        // Collect what client-1 receives.
        val seen = mutableListOf<ByteArray>()
        val collector = backgroundScope.async {
            clients[1].second.incoming.collect { seen += it.bytes }   // Swatch -> bytes
        }

        hub.sendTo(PeerId("client-0"), byteArrayOf(42))               // disclosure for seat 0 only
        runCurrent()

        assertEquals(0, seen.size, "client-1 must never observe a frame addressed to client-0")
        collector.cancel()
    }
}
```
> Use whichever accessor `Swatch` exposes for its bytes (the worker confirms — `incoming: Flow<Swatch>`). If a tiny private `hostedOverlayForTest` shim is awkward, call `hostedOverlay` directly with a seeded `Random`.

- [ ] **Step 3: Run it — expect compile/red**

Run: `./gradlew :kuilt-game:compileTestKotlinJvm`
Expected: FAIL — unresolved `hostedOverlay`/import until the gossip dep + test compile; the assertion itself should pass once it compiles (the invariant holds by construction — this test pins it against regression).

- [ ] **Step 4: Implement `gameHosted`**

```kotlin
// kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameHosted.kt
package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.InMemoryRaftStorage
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Host a turn-based game over a [ConnectionSource]: compose a star hub from accepted connections
 * (`hostedOverlay`, FullFanout) and run [gameHost] on it. Clients are unchanged — they `gameJoin`
 * over a `KtorClientLoom` seam exactly as today.
 *
 * Thin sugar over `hostedOverlay` + `gameHost`; advanced callers who need to interpose on the hub
 * seam drop to `hostedOverlay` directly.
 */
public suspend fun CoroutineScope.gameHosted(
    selfId: PeerId,
    source: ConnectionSource,
    peerCount: Int,
    returnAt: ReturnPolicy = ReturnPolicy.FullMembership,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    livenessConfig: HeartbeatConfig? = null,
    random: Random = Random(0L),
    clock: () -> Instant = { Clock.System.now() },
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession {
    val dispatcher = coroutineContext[ContinuationInterceptor]!!
    val overlay = hostedOverlay(selfId, source, dispatcher, random, clock)
    return gameHost(
        seam = overlay,
        peerCount = peerCount,
        returnAt = returnAt,
        storage = storage,
        raftConfig = raftConfig,
        livenessConfig = livenessConfig,
        clock = clock,
        identity = identity,
    )
}
```
> Match `gameHost`'s real parameter names/types (grounded above); forward the subset that makes sense for a hosted bootstrap. Confirm `ReturnPolicy`, `ClientIdentity`, `RaftStorage` import paths from `GameNode.kt`.

- [ ] **Step 5: Run the leak test + a `gameHosted` smoke check**

Run: `./gradlew :kuilt-game:jvmTest --tests "*GameHostedLeakTest"`
Expected: PASS.
(Optional: add a `gameHosted` end-to-end convergence smoke test through `InMemoryConnectionSource` + `gameJoin` clients if a lightweight one fits the existing `HostedHubReplicationTest` harness — otherwise the leak test + PR-A's composition coverage suffice.)

- [ ] **Step 6: Full verification & commit**

Run: `./gradlew :kuilt-game:build detektAll` then `./gradlew build`
```bash
git commit -am "feat(kuilt-game): gameHosted — run a game over accepted connections

gameHosted(selfId, source, peerCount) = gameHost(hostedOverlay(...)). Adds the
kuilt-game -> kuilt-gossip main dependency. Pins the disclosure invariant: a
per-seat sendTo is never relayed by FullFanout.

Part of #794"
```
PR body: `Part of #794`, `Prev: #<PR-B>. (Last in stack.)` Auto-merge once green.

- [ ] **Step 7: After C lands**
- Post a kuilt-side note on the downstream consumer's hub-composition issue/PR that the prerequisite shipped (describe kuilt's own surface only — no cross-repo specifics in kuilt artifacts per the references policy; the note goes on the *consumer's* tracker).
- File the deferred follow-up issue: **principal/attestation on the hub-accept path** (thread a `principalExtractor` through `KtorConnectionSource` → `hostedOverlay` → `gameHosted`, mirroring `KtorServerLoom`). `Part of #794`.

---

## Self-Review

- **Spec coverage:** Piece 1 (`ConnectionSource`) → Task A Step 1. Piece 2 (`KtorConnectionSource`) → Task B. Piece 3 (`hostedOverlay`) → Task A Steps 4–5. Piece 4 (`gameHosted`) → Task C Step 4. Piece 5 (`InMemoryConnectionSource` + leak test) → Task A Step 4 + Task C Step 2. `inMemoryStarOf` reimplemented on top → Task A Step 7. Deferred attestation → Task C Step 7. Three stacked PRs, each independently green → Tasks A/B/C. All covered.
- **Type consistency:** `ConnectionSource.accept(): Connection`, `InMemoryConnectionSource.offer/accept`, `hostedOverlay(selfId, source, dispatcher, random, clock): Seam`, `Star.source`, `gameHosted(selfId, source, peerCount, …)` — names used identically across tasks.
- **Open verification points flagged for the worker (bounded, not placeholders):** the WS handler-hold mechanism (`closeReason.await()` vs session-job await) in Task B Step 3; `Swatch` bytes accessor in Task C Step 2; exact `gameHost` param subset to forward in Task C Step 4. Each names the exact thing to confirm and the fallback.
