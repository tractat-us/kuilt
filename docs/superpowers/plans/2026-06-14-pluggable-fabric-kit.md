# Pluggable Fabric Kit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide reusable `Seam` implementations (`Conn` SPI, `identified`/`handshaking` 2-peer seams, `framed()` stream helper, `MeshSeam`) so a consumer adapts a proprietary RPC into a first-class kuilt fabric in ~30 lines — demonstrated end-to-end by a `:kuilt-tcp` fabric and a docs tutorial.

**Architecture:** A point-to-point `Conn` abstraction sits beneath the existing `Seam` contract. `identified` is the byte-transparent 2-peer primitive; `handshaking` wraps it via a one-shot `Hello` preamble; `framed()` adapts a kotlinx-io `Source`/`Sink` into a `Conn`; `MeshSeam` aggregates many handshaked point-to-point links into one N-peer seam. No change to `Seam`/`Loom`/`Swatch`.

**Tech Stack:** Kotlin Multiplatform, coroutines (`StateFlow`/`Channel`/`Flow`), kotlinx-io (stream module only), Ktor `network` (TCP fabric), the existing `SeamConformanceSuite`.

**Spec:** `docs/superpowers/specs/2026-06-14-pluggable-fabric-kit-design.md`

---

## File structure

| Path | Responsibility |
|------|----------------|
| `kuilt-core/.../core/fabric/Conn.kt` | The `Conn` message SPI |
| `kuilt-core/.../core/fabric/Hello.kt` | `Hello` preamble value + encode/decode |
| `kuilt-core/.../core/fabric/LinkSeam.kt` | `identified(...)` primitive 2-peer seam + internal writer |
| `kuilt-core/.../core/fabric/Handshaking.kt` | `handshaking(...)` — preamble then delegate to `identified` |
| `kuilt-core/.../core/fabric/MeshSeam.kt` | `meshSeam(...)` N-peer aggregation + dedup |
| `kuilt-test/.../test/fabric/ConnPair.kt` | In-memory wired `Conn` pair + `ConnLoom` test harness |
| `kuilt-conformance/.../IdentifiedConformanceTest.kt` | Runs `SeamConformanceSuite` over `identified` |
| `kuilt-conformance/.../HandshakingConformanceTest.kt` | Runs the suite over `handshaking` |
| `kuilt-conformance/.../MeshConformanceSuite.kt` + `MeshConformanceTest.kt` | N-peer contract suite + run |
| `kuilt-stream/` (new module) | `framed(source, sink, maxFrameSize): Conn` + length-prefix codec |
| `kuilt-tcp/` (new module) | TCP `Loom` (dial/accept) + worked proprietary-RPC example |
| `docs/extending-fabrics.md` | Tutorial: implement a custom RPC fabric, step by step |

Package root for new core code: `us.tractat.kuilt.core.fabric`.

---

## Task 1: `Conn` SPI

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Conn.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.flow.Flow

/**
 * A point-to-point, message-oriented duplex link between exactly two peers.
 *
 * The minimal SPI a message transport (WebSocket, gRPC bidi stream, Multipeer,
 * Nearby) implements to become a kuilt fabric. Stream transports (TCP) do not
 * implement this directly — they provide a kotlinx-io Source/Sink and use
 * `:kuilt-stream`'s `framed()` to obtain a `Conn`.
 *
 * Each frame is a whole message; the link preserves frame boundaries and FIFO order.
 */
public interface Conn {
    /** Send one whole message. Suspends until the transport accepts it (backpressure). */
    public suspend fun send(frame: ByteArray)

    /** Whole messages received from the peer, in order. Single-collection. */
    public val incoming: Flow<ByteArray>

    /** Close the link. Idempotent. Completes [incoming]. */
    public suspend fun close()
}
```

- [ ] **Step 2: Verify it compiles**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-core:compileKotlinJvm`
Expected: BUILD SUCCESSFUL (explicitApi satisfied — every member is `public`).

- [ ] **Step 3: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Conn.kt
git commit -m "feat(kuilt-core): add Conn point-to-point message SPI"
```

---

## Task 2: In-memory `Conn` pair + `ConnLoom` test harness

A wired pair of `Conn`s (each end's `send` feeds the other's `incoming`) and a `Loom`
that wraps them in `identified` seams, so the conformance suite (which tests `Loom`s)
can drive the new seams. Lives in `:kuilt-test` so conformance and unit tests reuse it.

**Files:**
- Create: `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/ConnPair.kt`

- [ ] **Step 1: Write the test (round-trip through the pair)**

```kotlin
// kuilt-test/src/commonTest/.../fabric/ConnPairTest.kt
package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConnPairTest {
    @Test
    fun framesCrossToTheOtherEnd() = runTest {
        val (a, b) = connPair()
        a.send(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), b.incoming.first())
    }
}
```

- [ ] **Step 2: Run it; expect failure (`connPair` unresolved)**

Run: `./gradlew :kuilt-test:jvmTest --tests "*ConnPairTest*"`
Expected: FAIL — unresolved reference `connPair`.

- [ ] **Step 3: Implement `connPair()` and `ConnLoom`**

```kotlin
package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.fabric.Conn

/** Two Conns whose sends cross to each other's `incoming`. In-memory, no network. */
public fun connPair(): Pair<Conn, Conn> {
    val aToB = Channel<ByteArray>(Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(Channel.UNLIMITED)
    return ChannelConn(out = aToB, inn = bToA) to ChannelConn(out = bToA, inn = aToB)
}

private class ChannelConn(
    private val out: Channel<ByteArray>,
    private val inn: Channel<ByteArray>,
) : Conn {
    override suspend fun send(frame: ByteArray) { out.send(frame) }
    override val incoming: Flow<ByteArray> = inn.receiveAsFlow()
    override suspend fun close() { out.close() }
}
```

- [ ] **Step 4: Run test; expect PASS**

Run: `./gradlew :kuilt-test:jvmTest --tests "*ConnPairTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/ConnPair.kt \
        kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/fabric/ConnPairTest.kt
git commit -m "test(kuilt-test): in-memory Conn pair fixture"
```

---

## Task 3: `identified` — the primitive 2-peer Seam

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/LinkSeamTest.kt`

- [ ] **Step 1: Write the failing test (lifecycle + ordered round-trip)**

```kotlin
package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.fabric.connPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinkSeamTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")

    @Test
    fun wovenAtConstructionAndDeliversFromRemote() = runTest {
        val (mine, theirs) = connPair()
        val seam = identified(mine, self, remote, UnconfinedTestDispatcher(testScheduler))
        assertIs<SeamState.Woven>(seam.state.value)
        assertEquals(setOf(self, remote), seam.peers.value)
        theirs.send(byteArrayOf(9))
        val swatch = seam.incoming.first()
        assertContentEquals(byteArrayOf(9), swatch.payload)
        assertEquals(remote, swatch.sender)
    }

    @Test
    fun concurrentBroadcastsArriveInSendOrder() = runTest {
        val (mine, theirs) = connPair()
        val seam = identified(mine, self, remote, UnconfinedTestDispatcher(testScheduler))
        repeat(3) { seam.broadcast(byteArrayOf(it.toByte())) }
        val got = theirs.incoming.take(3).toList().map { it.single() }
        assertEquals(listOf<Byte>(0, 1, 2), got)
    }
}
```

- [ ] **Step 2: Run; expect failure (`identified` unresolved)**

Run: `./gradlew :kuilt-core:jvmTest --tests "*LinkSeamTest*"`
Expected: FAIL — unresolved `identified`.

- [ ] **Step 3: Implement `identified`**

```kotlin
package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.coroutines.CoroutineContext

/**
 * A byte-transparent 2-peer [Seam] over a [Conn] whose two identities are known.
 * `broadcast` == `sendTo(remoteId)`. Woven at construction; Torn on conn EOF/error
 * or [close]. Concurrent sends are serialized through an internal channel + single
 * writer so wire order matches call order.
 *
 * @param dispatcher confines internal state; production uses the confined default,
 *   tests inject `UnconfinedTestDispatcher(testScheduler)`.
 */
public fun identified(
    conn: Conn,
    selfId: PeerId,
    remoteId: PeerId,
    dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
): Seam = LinkSeam(conn, selfId, remoteId, dispatcher)

internal class LinkSeam(
    private val conn: Conn,
    override val selfId: PeerId,
    private val remoteId: PeerId,
    dispatcher: CoroutineContext,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _peers = MutableStateFlow(setOf(selfId, remoteId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val inbox = Channel<Swatch>(Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = inbox.receiveAsFlow()

    // Single-writer outbound queue: concurrent broadcast/sendTo enqueue here;
    // one coroutine drains in FIFO order to conn.send.
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)

    private var closed = false
    private var seq = 0L

    init {
        scope.launch { writeLoop() }
        scope.launch { readLoop() }
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(!closed) { "Seam for $selfId is closed" }
        outbox.send(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(!closed) { "Seam for $selfId is closed" }
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        outbox.send(payload)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        _peers.update { setOf(selfId) }
        _state.value = SeamState.Torn(reason)
        outbox.close()
        inbox.close()
        conn.close()
    }

    private suspend fun writeLoop() {
        for (frame in outbox) conn.send(frame)
    }

    private suspend fun readLoop() {
        try {
            conn.incoming.collect { bytes ->
                if (!closed) inbox.trySend(Swatch(payload = bytes, sender = remoteId, sequence = ++seq))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // remote dropped — fall through to teardown
        } finally {
            if (!closed) {
                closed = true
                _peers.update { setOf(selfId) }
                _state.value = SeamState.Torn(CloseReason.RemoteRequested)
                inbox.close()
                outbox.close()
            }
        }
    }
}
```

- [ ] **Step 4: Run; expect PASS**

Run: `./gradlew :kuilt-core:jvmTest --tests "*LinkSeamTest*"`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/LinkSeamTest.kt
git commit -m "feat(kuilt-core): identified() byte-transparent 2-peer Seam"
```

---

## Task 4: `identified` conformance

**Files:**
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/IdentifiedConformanceTest.kt`
- Modify: `kuilt-test/.../fabric/ConnPair.kt` — add `identifiedLoomPair()` helper

- [ ] **Step 1: Add the `Loom` pair helper to `:kuilt-test`**

```kotlin
// append to ConnPair.kt
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.identified

/**
 * A host/joiner Loom pair wired by one in-memory [connPair]: host weaves an
 * `identified` seam over one end, joiner over the other. For driving
 * `SeamConformanceSuite` against the LinkSeam primitive.
 */
public fun identifiedLoomPair(): Pair<Loom, Loom> {
    val (hostConn, joinerConn) = connPair()
    val host = ConnLoom(PeerId("host"), PeerId("joiner"), hostConn)
    val joiner = ConnLoom(PeerId("joiner"), PeerId("host"), joinerConn)
    return host to joiner
}

private class ConnLoom(
    private val self: PeerId,
    private val remote: PeerId,
    private val conn: Conn,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = identified(conn, self, remote)
}
```

- [ ] **Step 2: Write the conformance test**

```kotlin
package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.test.fabric.identifiedLoomPair

/** The `identified` 2-peer primitive satisfies the seam contract. */
class IdentifiedConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> = identifiedLoomPair()
}
```

- [ ] **Step 3: Run the full suite; expect PASS**

Run: `./gradlew :kuilt-conformance:jvmTest --tests "*IdentifiedConformanceTest*"`
Expected: PASS — every contract invariant green. If `incomingCompletesWhenSeamCloses` fails, confirm `close()`/readLoop both close `inbox`.

- [ ] **Step 4: Commit**

```bash
git add kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/ConnPair.kt \
        kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/IdentifiedConformanceTest.kt
git commit -m "test(kuilt-conformance): identified passes SeamConformanceSuite"
```

> **Slice 1 (sub-issue) ends here** — `Conn` + `identified` + conformance. Foundation green before fan-out.

---

## Task 5: `Hello` preamble

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Hello.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/HelloTest.kt`

- [ ] **Step 1: Write the failing round-trip test**

```kotlin
package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals

class HelloTest {
    @Test
    fun encodeDecodeRoundTrips() {
        val id = PeerId("node-42")
        assertEquals(id, Hello.decode(Hello.encode(id)))
    }
}
```

- [ ] **Step 2: Run; expect failure**

Run: `./gradlew :kuilt-core:jvmTest --tests "*HelloTest*"`
Expected: FAIL — unresolved `Hello`.

- [ ] **Step 3: Implement (UTF-8 bytes of the PeerId string — the preamble carries only the id)**

```kotlin
package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId

/** The one-shot identity preamble: the first frame on a handshaking link. */
public object Hello {
    public fun encode(selfId: PeerId): ByteArray = selfId.value.encodeToByteArray()
    public fun decode(frame: ByteArray): PeerId = PeerId(frame.decodeToString())
}
```

- [ ] **Step 4: Run; expect PASS** — `./gradlew :kuilt-core:jvmTest --tests "*HelloTest*"`

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Hello.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/HelloTest.kt
git commit -m "feat(kuilt-core): Hello identity preamble codec"
```

---

## Task 6: `handshaking` — wraps `identified`

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Handshaking.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/HandshakingTest.kt`

- [ ] **Step 1: Write the failing test (each side learns the other's id; payload flows after)**

```kotlin
package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HandshakingTest {
    @Test
    fun learnsRemoteIdThenCarriesPayload() = runTest {
        val (a, b) = connPair()
        val seamA = async { handshaking(a, PeerId("A")) }
        val seamB = async { handshaking(b, PeerId("B")) }
        val sa = seamA.await(); val sb = seamB.await()
        assertEquals(setOf(PeerId("A"), PeerId("B")), sa.peers.value)
        sa.broadcast(byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), sb.incoming.first().payload)
    }
}
```

- [ ] **Step 2: Run; expect failure** — `./gradlew :kuilt-core:jvmTest --tests "*HandshakingTest*"`

- [ ] **Step 3: Implement — send preamble, await peer preamble, wrap the remaining conn in `identified`**

```kotlin
package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import kotlin.coroutines.CoroutineContext

/**
 * A 2-peer [Seam] for transports that do NOT carry identity out of band.
 * Sends a [Hello] preamble, awaits the peer's, then delegates to [identified]
 * over the post-preamble connection. Suspends until the peer's preamble arrives.
 */
public suspend fun handshaking(
    conn: Conn,
    selfId: PeerId,
    dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
): Seam {
    conn.send(Hello.encode(selfId))
    val remoteId = Hello.decode(conn.firstFrame())
    return identified(PreambleStrippedConn(conn), selfId, remoteId, dispatcher)
}

/** The peer's preamble is frame 0; app payloads are everything after it. */
private class PreambleStrippedConn(private val delegate: Conn) : Conn {
    override suspend fun send(frame: ByteArray) = delegate.send(frame)
    override val incoming: Flow<ByteArray> = delegate.incoming.drop(1)
    override suspend fun close() = delegate.close()
}
```

Add the `firstFrame()` helper next to the `Conn` interface in `Conn.kt`:

```kotlin
import kotlinx.coroutines.flow.first
/** Await the first inbound frame (the identity preamble). */
internal suspend fun Conn.firstFrame(): ByteArray = incoming.first()
```

> **Note for the worker:** `incoming` is single-collection. `firstFrame()` consumes
> emission 0; `PreambleStrippedConn` `drop(1)` makes the delegated `identified` seam's
> collector start at emission 1. Because `connPair` is `Channel`-backed (hot/buffered),
> the dropped first emission is the peer's `Hello` and nothing races. If a future
> `Conn` is cold/non-buffered, revisit by buffering the preamble read instead of
> double-collecting. Document this assumption in the KDoc.

- [ ] **Step 4: Run; expect PASS** — `./gradlew :kuilt-core:jvmTest --tests "*HandshakingTest*"`

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Handshaking.kt \
        kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Conn.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/HandshakingTest.kt
git commit -m "feat(kuilt-core): handshaking() wraps identified via Hello preamble"
```

---

## Task 7: `handshaking` conformance

**Files:**
- Modify: `kuilt-test/.../fabric/ConnPair.kt` — add `handshakingLoomPair()`
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/HandshakingConformanceTest.kt`

- [ ] **Step 1: Add `handshakingLoomPair()` (each Loom weaves a handshaking seam over its end; the suite calls host/join concurrently so both preambles cross)**

```kotlin
// append to ConnPair.kt
import us.tractat.kuilt.core.fabric.handshaking

public fun handshakingLoomPair(): Pair<Loom, Loom> {
    val (hostConn, joinerConn) = connPair()
    return HandshakeLoom(PeerId("host"), hostConn) to HandshakeLoom(PeerId("joiner"), joinerConn)
}

private class HandshakeLoom(private val self: PeerId, private val conn: Conn) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = handshaking(conn, self)
}
```

> **Worker note:** `SeamConformanceSuite` weaves host and joiner via `async` (see its
> `newLoomPair` usage) so the two `handshaking` calls run concurrently and their
> preambles cross. If any test deadlocks, confirm the suite weaves concurrently rather
> than `host()` fully before `join()`; if it serializes, override the harness to weave
> both ends in parallel before returning.

- [ ] **Step 2: Write the conformance test**

```kotlin
package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.test.fabric.handshakingLoomPair

class HandshakingConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> = handshakingLoomPair()
}
```

- [ ] **Step 3: Run; expect PASS** — `./gradlew :kuilt-conformance:jvmTest --tests "*HandshakingConformanceTest*"`

- [ ] **Step 4: Commit**

```bash
git add kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/ConnPair.kt \
        kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/HandshakingConformanceTest.kt
git commit -m "test(kuilt-conformance): handshaking passes SeamConformanceSuite"
```

> **Slice 2 (sub-issue) ends here** — `Hello` + `handshaking` + conformance.

---

## Task 8: `:kuilt-stream` module + `framed()`

**Files:**
- Modify: `settings.gradle.kts` — `include(":kuilt-stream")`
- Modify: `gradle/libs.versions.toml` — add `kotlinx-io-core`
- Create: `kuilt-stream/build.gradle.kts`
- Create: `kuilt-stream/src/commonMain/kotlin/us/tractat/kuilt/stream/Framed.kt`
- Test: `kuilt-stream/src/commonTest/kotlin/us/tractat/kuilt/stream/FramedTest.kt`

- [ ] **Step 1: Add kotlinx-io to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]` add `kotlinx-io = "0.6.0"` (verify it is the latest stable at implementation time), and under `[libraries]`:

```toml
kotlinx-io-core = { module = "org.jetbrains.kotlinx:kotlinx-io-core", version.ref = "kotlinx-io" }
```

- [ ] **Step 2: Register the module**

In `settings.gradle.kts`, add `include(":kuilt-stream")` alongside the other `include(...)` lines.

- [ ] **Step 3: Create `kuilt-stream/build.gradle.kts`**

```kotlin
plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
```

- [ ] **Step 4: Write the failing tests — round-trip, reassembly across reads, oversize-prefix rejected, truncated-at-EOF errors**

```kotlin
package us.tractat.kuilt.stream

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class FramedTest {
    @Test
    fun framesRoundTripThroughLengthPrefix() = runTest {
        val wire = Buffer()                 // shared in-memory pipe (sink writes, source reads)
        val conn = framed(source = wire, sink = wire, maxFrameSize = 1024)
        conn.send(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), conn.incoming.first())
    }

    @Test
    fun rejectsOversizePrefixWithoutAllocating() = runTest {
        val wire = Buffer()
        wire.writeInt(Int.MAX_VALUE)        // a hostile length prefix
        val conn = framed(source = wire, sink = wire, maxFrameSize = 16)
        assertFailsWith<FrameTooLargeException> { conn.incoming.toList() }
    }
}
```

- [ ] **Step 5: Run; expect failure** — `./gradlew :kuilt-stream:jvmTest`
Expected: FAIL — unresolved `framed`/`FrameTooLargeException`.

- [ ] **Step 6: Implement the codec (4-byte length prefix; bounded; pull-based read)**

```kotlin
package us.tractat.kuilt.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.EOFException
import kotlinx.io.readByteArray
import us.tractat.kuilt.core.fabric.Conn

public class FrameTooLargeException(size: Int, max: Int) :
    Exception("frame length $size exceeds max $max")

public const val DEFAULT_MAX_FRAME_SIZE: Int = 16 * 1024 * 1024

/**
 * Adapt a kotlinx-io [Source]/[Sink] byte duplex into a message [Conn] using a
 * 4-byte big-endian length prefix per frame. Owns reassembly (the Source blocks
 * until N bytes are available), a [maxFrameSize] cap (the prefix is validated
 * before any allocation), and partial-frame-at-EOF detection (loud, not silent).
 */
public fun framed(
    source: Source,
    sink: Sink,
    maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE,
): Conn = FramedConn(source, sink, maxFrameSize)

private class FramedConn(
    private val source: Source,
    private val sink: Sink,
    private val maxFrameSize: Int,
) : Conn {
    override suspend fun send(frame: ByteArray) {
        require(frame.size <= maxFrameSize) { "frame ${frame.size} exceeds max $maxFrameSize" }
        sink.writeInt(frame.size)
        sink.write(frame)
        sink.flush()
    }

    override val incoming: Flow<ByteArray> = flow {
        while (true) {
            val len = try { source.readInt() } catch (e: EOFException) { break }
            if (len < 0 || len > maxFrameSize) throw FrameTooLargeException(len, maxFrameSize)
            // readByteArray throws EOFException if the stream ends mid-frame — surface it.
            emit(source.readByteArray(len))
        }
    }

    override suspend fun close() {
        sink.close()
        source.close()
    }
}
```

> **Worker note:** kotlinx-io `Source.readInt()`/`readByteArray(n)` are blocking pull
> reads — they suspend the flow's collecting coroutine until bytes arrive, which is the
> whole reason the stream side is pull-based. Run the collection on an IO dispatcher in
> the real TCP fabric (Task 9), not the confined seam dispatcher.

- [ ] **Step 7: Run; expect PASS** — `./gradlew :kuilt-stream:jvmTest`

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml kuilt-stream/
git commit -m "feat(kuilt-stream): framed() adapts kotlinx-io Source/Sink to a Conn"
```

> **Slice 3 (sub-issue) ends here** — `:kuilt-stream` + `framed()`.

---

## Task 9: `:kuilt-tcp` fabric + proprietary-RPC example (the headline)

**Files:**
- Modify: `settings.gradle.kts` — `include(":kuilt-tcp")`
- Modify: `gradle/libs.versions.toml` — add `ktor-network`
- Create: `kuilt-tcp/build.gradle.kts`
- Create: `kuilt-tcp/src/commonMain/kotlin/us/tractat/kuilt/tcp/TcpLoom.kt`
- Create: `kuilt-tcp/src/jvmMain/kotlin/us/tractat/kuilt/tcp/TcpConn.kt` (adapts a Ktor socket to kotlinx-io Source/Sink → `framed()`)
- Test: `kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/TcpConformanceTest.kt` (loopback host/joiner)
- Test: `kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/TcpRoundTripTest.kt`

- [ ] **Step 1:** Register module + add `ktor-network` to the catalog (reuse the existing `ktor` version ref).
- [ ] **Step 2:** `build.gradle.kts` — `api(project(":kuilt-core"))`, `api(project(":kuilt-stream"))`, `jvmMain` gets `libs.ktor.network`; targets JVM (+Android) where raw TCP sockets exist.
- [ ] **Step 3:** Write a **loopback conformance test** first: `TcpConformanceTest : SeamConformanceSuite()` whose `newLoomPair()` binds a server socket on an ephemeral port, returns a host `Loom` (accepts one connection → `handshaking`) and a joiner `Loom` (connects → `handshaking`). Run: `./gradlew :kuilt-tcp:jvmTest --tests "*TcpConformanceTest*"` — expect FAIL (types missing).
- [ ] **Step 4:** Implement `TcpConn` (Ktor `aSocket(...).tcp()` → `openReadChannel()`/`openWriteChannel()` adapted to kotlinx-io `Source`/`Sink`, fed to `framed()`) and `TcpLoom.host`/`join` (accept/connect → `handshaking(framed(...), selfId)`). Keep transport-specific code minimal — this line count is the headline metric.
- [ ] **Step 5:** Run conformance + round-trip; expect PASS. Run full `./gradlew :kuilt-tcp:build`.
- [ ] **Step 6:** Add a runnable `proprietary-rpc-example` source (a `main()` or test) that stands in a fake "in-house RPC" (a plain socket) and shows the same `Conn`-or-`framed()` adapter producing a working seam — the artifact the tutorial cites.
- [ ] **Step 7: Commit** each of (conformance test) → (impl) → (example) separately.

> **Worker note:** size the slice. If the Ktor-socket↔kotlinx-io adapter balloons,
> stop at the green loopback conformance checkpoint, open a draft PR, and return a
> re-plan (what's done, what remains) per the EPIC re-plan contract — do not grind to a
> truncated marathon.

> **Slice 4 (sub-issue) ends here** — the primary goal. Verify the transport-specific
> code is ~30 lines and record the actual count in the PR body.

---

## Task 10: `docs/extending-fabrics.md` tutorial (explicit documentation pass)

**Files:**
- Create: `docs/extending-fabrics.md`
- Modify: `docs/architecture.md` — one cross-link to the new guide
- Modify: `CLAUDE.md` — add the guide to the doc pointers (optional)

- [ ] **Step 1:** Write the tutorial as a narrative with two tracks, each a copy-pasteable progression:
  - **Message-RPC track:** implement `Conn` (3 methods) → wrap in `handshaking` → write a dialing/accepting `Loom`. Show the full code.
  - **Stream-RPC track (TCP):** provide a kotlinx-io `Source`/`Sink` → `framed()` → `handshaking` → `Loom`. Reference the real `:kuilt-tcp` code by `file:line`.
  - Cover the three gotchas explicitly: framing (stream only — `framed()` handles it), identity (out-of-band → `identified`; in-band → `handshaking`), lifecycle (`Woven`/`Torn` mapping).
  - End with "prove it" — subclass `SeamConformanceSuite`, implement `newLoomPair()`, go green. Show the `:kuilt-tcp` conformance test as the template.
  - A closing section on the cluster path (`MeshSeam`) once Task 11 lands; until then, a one-line forward pointer.
- [ ] **Step 2:** Verify every code block compiles by lifting it from the actual `:kuilt-tcp`/`:kuilt-core` sources (no invented APIs). Cross-check each type name against the merged slices.
- [ ] **Step 3: Commit**

```bash
git add docs/extending-fabrics.md docs/architecture.md
git commit -m "docs: tutorial — implement a custom RPC fabric"
```

> This is a docs-only change → CI `ci-required` goes green without the KMP build. Land
> it after Task 9 so the tutorial can cite real `:kuilt-tcp` line numbers.

> **Slice 7 (sub-issue) ends here.**

---

## Task 11: `MeshSeam` + `MeshConformanceSuite` (phase 2 — cluster)

> Phase 2. Must not gate the headline (Tasks 1–10). This task is a finer-grained
> sub-plan written at dispatch time; the outline below is the contract.

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt`
- Create: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/MeshConformanceSuite.kt`
- Create: `kuilt-conformance/src/commonTest/.../MeshConformanceTest.kt` (in-memory `connPair`-backed N-peer harness)
- Create: TCP cluster example in `:kuilt-tcp`

- [ ] **Step 1:** `MeshConformanceSuite` — abstract `newMeshOfSize(n: Int): List<Seam>`; tests: every peer's `peers` converges to the other n−1; `broadcast` from one reaches all; `sendTo` routes to exactly one; a peer leaving updates every survivor's roster; two simultaneous dials between the same pair dedup to one link (lower `PeerId` wins). Write these RED first.
- [ ] **Step 2:** Implement `meshSeam(selfId, dialer, acceptor, seeds, dispatcher)` — `Map<PeerId, Conn>` of handshaked links; `Hello` per link to learn ids; dedup lower-`PeerId`-wins; `peers` derived from live links; `broadcast` fans out, `sendTo` looks up; per-link error drops one peer (seam stays live); all state confined to the injected dispatcher. Follow `CompositeSeam.kt` for the confinement pattern.
- [ ] **Step 3:** Build an in-memory mesh harness over `connPair` for the conformance run; go green on JVM, then `allTests`.
- [ ] **Step 4:** TCP cluster example + extend `docs/extending-fabrics.md` cluster section.
- [ ] **Step 5: Commit** per RED→GREEN cycle; one behaviour per commit.

> **Slice 5 (sub-issue) ends here.**

---

## Task 12 (optional): refactor `:kuilt-websocket` onto `identified`

> Slice 6. Orthogonal, droppable. Pure internal refactor — WS keeps its exact
> byte-transparent wire by using `identified` (out-of-band identity), so this is a
> no-wire-change cleanup that deletes `WebSocketSeam`'s hand-rolled receive loop.

- [ ] **Step 1:** Implement a `WebSocketConn : Conn` wrapping `DefaultWebSocketSession` (binary frames in/out; the receive loop becomes the `Conn.incoming` flow).
- [ ] **Step 2:** Replace `WebSocketSeam`'s body with `identified(WebSocketConn(session), selfId, remoteId)`. Keep `KtorClientLoom`/`KtorServerLoom` unchanged externally.
- [ ] **Step 3:** Existing `WebSocketConformanceTest` + round-trip stay green with no edits — that is the proof the refactor preserved behaviour. Run `./gradlew :kuilt-websocket:build`.
- [ ] **Step 4: Commit.**

> **Slice 6 (sub-issue) ends here.**

---

## Final verification

- [ ] `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build` — full multiplatform build + all tests green (catches Android-variant, check-guard, and cross-module failures `jvmTest` hides).
- [ ] Confirm zero `explicitApi()` violations (the build fails on missing visibility modifiers).
- [ ] Record the `:kuilt-tcp` transport-specific line count in the PR body (the headline acceptance metric).
