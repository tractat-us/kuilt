# Fabric Resilience Testing — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the missing fabric-level reconnect / lifecycle-flap testing primitive and the resilience tests it unlocks, so consumers can be verified against a `Seam` that flaps `Woven→Weaving→Woven` and flap-then-`Torn`.

**Architecture:** A `FlakyLifecycleSeam` wrapper that owns its own `state` (unlike `FaultySeam`, which delegates it) and gates the contract on it — `peers` collapses to `{selfId}` and inbound delivery suspends while `Weaving`. A `FlakyLifecycleLoom` mirrors `FaultyLoom`; a seeded `FlapSchedule` drives sustained chaos in virtual time. Composes with the existing `FaultProfile`/`FaultySeam` frame-fault kit.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (`StateFlow`/`Flow`, `runTest` virtual time), `kotlin.test`. JDK 21 (`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`).

**Design spec:** `docs/superpowers/specs/2026-05-31-fabric-resilience-testing-design.md`. Read it first.

**Scope:** Foundation only — issues #65 (Part A, the primitive) and #66 (Part B, the tests). Composite/Ply resilience (#67, Part C) is **out of scope here** — it folds into the Ply work after the composite (#62) lands.

**Conventions:** `explicitApi()` is enforced (explicit `public`). Test methods use backtick descriptive names (no `test` prefix); multi-assert tests use a file-private `assertAll`. All timing via `kotlinx.coroutines.delay` under `runTest`; all randomness seeded.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `kuilt-core/.../core/FlakyLifecycleSeam.kt` (create) | The lifecycle-flap `Seam` wrapper + `FlakyLifecycleLoom`. |
| `kuilt-core/.../core/FlapSchedule.kt` (create) | Seeded, virtual-time soak driver. |
| `kuilt-core/.../core/FlakyLifecycleSeamTest.kt` (create, commonTest) | Unit tests for the primitive (Part A). |
| `kuilt-core/.../core/LifecycleContractTest.kt` (create, commonTest) | Consumer-contract scenario test (Part B.1). |
| `kuilt-core/.../core/ResilienceSoakTest.kt` (create, commonTest) | Composed flap + frame-fault soak (Part B.3). |
| `kuilt-session/.../session/RoomLifecycleFlapTest.kt` (create, commonTest) | `SeamRoom` survives a real flap (Part B.2). |

Path prefix `kuilt-core/.../core/` = `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/` (tests under `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/`). Session tests under `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/`.

---

## PART A — the reconnect primitive (issue #65)

### Task 1: `FlakyLifecycleSeam` core — state ownership + gating

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlakyLifecycleSeamTest {
    @Test
    fun `enterWeaving collapses peers to self and recover restores`() = runTest {
        val mem = InMemoryLoom()
        val raw = mem.host(Pattern("host"))
        mem.join(InMemoryTag("joiner"))            // a second peer on the mesh
        val seam = FlakyLifecycleSeam(raw, backgroundScope)
        assertTrue(seam.peers.value.size >= 2, "Woven: sees the joiner")

        seam.enterWeaving()
        assertIs<SeamState.Weaving>(seam.state.value)
        assertEquals(setOf(seam.selfId), seam.peers.first { it == setOf(seam.selfId) })

        seam.recover()
        assertIs<SeamState.Woven>(seam.state.value)
        assertTrue(seam.peers.first { it.size >= 2 }.size >= 2, "recover refills peers")
    }

    @Test
    fun `broadcast while Weaving is a no-op and after recover delivers`() = runTest {
        val mem = InMemoryLoom()
        val a = mem.host(Pattern("a"))
        val b = mem.join(InMemoryTag("b"))
        val flaky = FlakyLifecycleSeam(a, backgroundScope)

        flaky.enterWeaving()
        flaky.broadcast(byteArrayOf(1))            // no-op: link down
        flaky.recover()
        flaky.broadcast(byteArrayOf(2))            // delivered

        // b only ever sees the post-recover frame.
        val first = b.incoming.first()
        assertTrue(first.payload.contentEquals(byteArrayOf(2)), "weaving send dropped; recover send arrives")
    }

    @Test
    fun `tear drives terminal Torn and sends throw`() = runTest {
        val mem = InMemoryLoom()
        val seam = FlakyLifecycleSeam(mem.host(Pattern("host")), backgroundScope)
        seam.tear(CloseReason.Unreachable)
        assertEquals(SeamState.Torn(CloseReason.Unreachable), seam.state.value)
        assertFailsWith<IllegalStateException> { seam.broadcast(byteArrayOf(9)) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: FAIL — `FlakyLifecycleSeam` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * A [Seam] wrapper that owns its own [state] (unlike [FaultySeam], which delegates
 * it) so tests can drive a transport link through `Woven → Weaving → Woven` flaps
 * and flap-then-[SeamState.Torn] escalation.
 *
 * While [SeamState.Weaving] (models a link-down window): [peers] collapses to
 * `{selfId}`, [broadcast] is the contract's defined no-op, and inbound delivery is
 * suspended. [recover] restores delivery and refills [peers] from the delegate.
 *
 * Composes with [FaultySeam] — wrap lifecycle outside frame-faults:
 * `FlakyLifecycleSeam(FaultySeam(realSeam), scope)`. All timing is virtual-time
 * driven ([kotlinx.coroutines.delay]); no wall-clock.
 */
public class FlakyLifecycleSeam(
    private val delegate: Seam,
    private val scope: CoroutineScope,
) : Seam {
    private val _state = MutableStateFlow(delegate.state.value)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    override val selfId: PeerId get() = delegate.selfId

    private val _peers = MutableStateFlow(peersFor(_state.value, delegate.peers.value))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    init {
        // peers reflects both the delegate's membership and our lifecycle gate.
        combine(delegate.peers, _state) { peers, st -> peersFor(st, peers) }
            .onEach { _peers.value = it }
            .launchIn(scope)
        // Inbound pump: deliver only while Woven; drop while Weaving; stop when Torn.
        scope.launch {
            delegate.incoming.collect { frame ->
                if (_state.value is SeamState.Woven) incomingChannel.trySend(frame)
            }
            incomingChannel.close()
        }
    }

    private fun peersFor(st: SeamState, delegatePeers: Set<PeerId>): Set<PeerId> =
        if (st is SeamState.Woven) delegatePeers else setOf(delegate.selfId)

    /** Woven → Weaving (held until [recover] or [tear]). */
    public fun enterWeaving() {
        if (_state.value !is SeamState.Torn) _state.value = SeamState.Weaving
    }

    /** Weaving → Woven. */
    public fun recover() {
        if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven
    }

    /** → Torn(reason). Terminal. */
    public fun tear(reason: CloseReason = CloseReason.Unreachable) {
        if (_state.value !is SeamState.Torn) {
            _state.value = SeamState.Torn(reason)
            incomingChannel.close()
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        when (_state.value) {
            is SeamState.Torn -> error("seam is Torn")
            is SeamState.Weaving -> Unit // defined no-op: link down, no peers
            is SeamState.Woven -> delegate.broadcast(payload)
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (_state.value is SeamState.Torn) error("seam is Torn")
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        delegate.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason) {
        tear(reason)
        delegate.close(reason)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt
git commit --no-gpg-sign -m "feat(core): FlakyLifecycleSeam — state ownership + Weaving gating"
```

---

### Task 2: `blip` + `flapThenTear` (virtual-time sugar)

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt`
- Modify: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt`

- [ ] **Step 1: Add failing tests**

Add the import `import kotlin.time.Duration.Companion.milliseconds` and these tests:

```kotlin
    @Test
    fun `blip returns to Woven after the weaving window`() = runTest {
        val mem = InMemoryLoom()
        val seam = FlakyLifecycleSeam(mem.host(Pattern("host")), backgroundScope)
        val states = mutableListOf<SeamState>()
        val job = kotlinx.coroutines.launch { seam.state.collect { states.add(it) } }

        seam.blip(weavingFor = 200.milliseconds)

        assertIs<SeamState.Woven>(seam.state.value)
        assertTrue(states.contains(SeamState.Weaving), "passed through Weaving")
        job.cancel()
    }

    @Test
    fun `flapThenTear ends terminal after N flaps`() = runTest {
        val mem = InMemoryLoom()
        val seam = FlakyLifecycleSeam(mem.host(Pattern("host")), backgroundScope)
        seam.flapThenTear(flaps = 3, weavingFor = 50.milliseconds, reason = CloseReason.Unreachable)
        assertEquals(SeamState.Torn(CloseReason.Unreachable), seam.state.value)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: FAIL — `blip`/`flapThenTear` unresolved.

- [ ] **Step 3: Implement (add to `FlakyLifecycleSeam`)**

Add the import `import kotlinx.coroutines.delay` and `import kotlin.time.Duration` and these methods:

```kotlin
    /** Woven → Weaving → (after [weavingFor] of virtual time) → Woven. */
    public suspend fun blip(weavingFor: Duration) {
        enterWeaving()
        delay(weavingFor)
        recover()
    }

    /** Flap [flaps] times, then escalate to terminal Torn([reason]). */
    public suspend fun flapThenTear(
        flaps: Int,
        weavingFor: Duration,
        reason: CloseReason = CloseReason.Unreachable,
    ) {
        repeat(flaps) { blip(weavingFor) }
        tear(reason)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt
git commit --no-gpg-sign -m "feat(core): FlakyLifecycleSeam blip + flapThenTear"
```

---

### Task 3: `FlakyLifecycleLoom`

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt`
- Modify: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt`

- [ ] **Step 1: Add the failing test**

```kotlin
    @Test
    fun `loom exposes woven seams in order`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val host = loom.host(Pattern("host"))
        loom.join(InMemoryTag("joiner"))
        assertEquals(2, loom.links.size)
        assertEquals(host.selfId, loom.links[0].selfId)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: FAIL — `FlakyLifecycleLoom` unresolved.

- [ ] **Step 3: Implement (append to the same file)**

```kotlin
/**
 * A [Loom] wrapper that produces [FlakyLifecycleSeam]s and exposes them (in weave
 * order) so scenario tests can drive specific links. Mirrors [FaultyLoom].
 */
public class FlakyLifecycleLoom(
    private val delegate: Loom,
    private val scope: CoroutineScope,
) : Loom {
    private val _links = mutableListOf<FlakyLifecycleSeam>()

    /** All [FlakyLifecycleSeam]s created so far, in creation order. */
    public val links: List<FlakyLifecycleSeam> get() = _links.toList()

    override suspend fun weave(rendezvous: Rendezvous): FlakyLifecycleSeam =
        FlakyLifecycleSeam(delegate.weave(rendezvous), scope).also { _links.add(it) }

    override fun availability(): FabricAvailability = delegate.availability()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt
git commit --no-gpg-sign -m "feat(core): FlakyLifecycleLoom"
```

---

### Task 4: `FlapSchedule` + `drive`

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlapSchedule.kt`
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt`
- Modify: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt`

- [ ] **Step 1: Add the failing test**

```kotlin
    @Test
    fun `FlapSchedule with giveUpAfter ends Torn deterministically`() = runTest {
        val mem = InMemoryLoom()
        val seam = FlakyLifecycleSeam(mem.host(Pattern("host")), backgroundScope)
        val schedule = FlapSchedule(
            seed = 1L,
            meanUptime = 1000.milliseconds,
            meanDowntime = 200.milliseconds,
            giveUpAfter = 2,
        )
        seam.drive(schedule)
        assertEquals(SeamState.Torn(CloseReason.Unreachable), seam.state.value)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: FAIL — `FlapSchedule`/`drive` unresolved.

- [ ] **Step 3: Write `FlapSchedule.kt`**

```kotlin
package us.tractat.kuilt.core

import kotlin.time.Duration

/**
 * Declarative, seeded driver for sustained lifecycle chaos. Consumed by
 * [FlakyLifecycleSeam.drive]. Durations are seed-jittered (±50%) around the means
 * and applied via virtual-time delay, so runs are deterministic.
 *
 * @param giveUpAfter number of flaps before escalating to Torn(Unreachable); 0 = never tear.
 */
public data class FlapSchedule(
    val seed: Long,
    val meanUptime: Duration,
    val meanDowntime: Duration,
    val giveUpAfter: Int,
)
```

- [ ] **Step 4: Implement `drive` (add to `FlakyLifecycleSeam`)**

Add `import kotlin.random.Random` and:

```kotlin
    /**
     * Run [schedule] in [scope]: alternate Woven (≈meanUptime) and Weaving
     * (≈meanDowntime), seed-jittered, for [FlapSchedule.giveUpAfter] flaps, then
     * tear. If `giveUpAfter == 0`, flaps indefinitely until [scope] is cancelled.
     */
    public suspend fun drive(schedule: FlapSchedule) {
        val rng = Random(schedule.seed)
        fun jitter(d: Duration): Duration = d * (0.5 + rng.nextDouble())
        var flaps = 0
        while (schedule.giveUpAfter == 0 || flaps < schedule.giveUpAfter) {
            delay(jitter(schedule.meanUptime))
            blip(jitter(schedule.meanDowntime))
            flaps++
        }
        tear(CloseReason.Unreachable)
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*FlakyLifecycleSeamTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlapSchedule.kt kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeamTest.kt
git commit --no-gpg-sign -m "feat(core): FlapSchedule seeded soak driver"
```

---

## PART B — resilience tests (issue #66)

### Task 5: Lifecycle contract scenario test

End-to-end consumer guarantee: across a blip, in-flight-while-Weaving sends vanish but order of the surviving stream is preserved.

**Files:**
- Create: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/LifecycleContractTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleContractTest {
    @Test
    fun `order is preserved across a blip and weaving sends do not arrive`() = runTest {
        val mem = InMemoryLoom()
        val a = FlakyLifecycleSeam(mem.host(Pattern("a")), backgroundScope)
        val b = mem.join(InMemoryTag("b"))

        val received = async { b.incoming.take(2).toList() }

        a.broadcast(byteArrayOf(1))           // Woven → delivered
        a.enterWeaving()
        a.broadcast(byteArrayOf(99))          // Weaving → dropped (link down)
        a.recover()
        a.broadcast(byteArrayOf(2))           // Woven → delivered

        val frames = received.await()
        assertAll(
            { assertTrue(frames[0].payload.contentEquals(byteArrayOf(1))) },
            { assertTrue(frames[1].payload.contentEquals(byteArrayOf(2)), "99 (sent while Weaving) never arrives") },
        )
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
```

- [ ] **Step 2: Run test to verify it passes** (the primitive already exists)

Run: `./gradlew :kuilt-core:jvmTest --tests "*LifecycleContractTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/LifecycleContractTest.kt
git commit --no-gpg-sign -m "test(core): lifecycle contract — order preserved across a blip"
```

---

### Task 6: Session resilience — `SeamRoom` survives a real flap

**Files:**
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomLifecycleFlapTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FlakyLifecycleLoom
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.partition.HeartbeatConfig
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RoomLifecycleFlapTest {
    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )
    private val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

    @Test
    fun `room delivers again after the host seam flaps Woven-Weaving-Woven`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        val hostRoom = factory.host(Pattern("host"))
        val joinerRoom = factory.join(InMemoryTag("joiner"))

        // Both rooms reach a non-empty roster (admit handshake completed).
        hostRoom.roster.first { it.isNotEmpty() }

        // Flap the host's underlying seam (links[0] is the host).
        loom.links[0].blip(weavingFor = 150.milliseconds)

        // After recovery, a broadcast still reaches the joiner.
        hostRoom.broadcast(byteArrayOf(42))
        val frame = joinerRoom.incoming.first()
        assertTrue(frame.payload.contentEquals(byteArrayOf(42)), "delivery resumes after the flap")
    }

    @Test
    fun `joiner sees HostLost when the host seam escalates to Torn`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        val hostRoom = factory.host(Pattern("host"))
        val joinerRoom = factory.join(InMemoryTag("joiner"))
        hostRoom.roster.first { it.isNotEmpty() }

        // Tear the joiner's view of the host link (links[1] is the joiner's seam).
        loom.links[1].tear(CloseReason.Unreachable)

        val event = joinerRoom.events.first { it is MembershipEvent.HostLost }
        assertIs<MembershipEvent.HostLost>(event)
    }
}
```

> If the joiner-side `HostLost` does not fire purely from the seam tearing (the
> partition detector may key off heartbeat timeout, not `state`), advance virtual
> time past the timeout — `kotlinx.coroutines.test.advanceTimeBy(800)` — after the
> `tear`, mirroring `PartitionRoleTest`. Add that line if the first run hangs on
> the `events.first { … }` await. **This is the test surfacing a real question:**
> does the session layer react to `state == Torn` directly, or only to heartbeat
> loss? If only the latter, file a follow-up issue — reacting to `state` is faster
> and more direct than waiting for a heartbeat timeout.

- [ ] **Step 2: Run test**

Run: `./gradlew :kuilt-session:jvmTest --tests "*RoomLifecycleFlapTest"`
Expected: both PASS (apply the `advanceTimeBy` note if the second test stalls).

- [ ] **Step 3: Commit**

```bash
git add kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomLifecycleFlapTest.kt
git commit --no-gpg-sign -m "test(session): SeamRoom survives a real Woven-Weaving-Woven flap"
```

---

### Task 7: Soak test — composed flap + frame-fault converges

**Files:**
- Create: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/ResilienceSoakTest.kt`

- [ ] **Step 1: Write the test**

Composes lifecycle flap (outer) over frame-faults (inner) and asserts every frame sent **while Woven** is eventually delivered.

```kotlin
package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ResilienceSoakTest {
    @Test
    fun `frames sent while Woven are eventually delivered under flap plus loss`() = runTest {
        val mem = InMemoryLoom()
        // Inner frame-faults (probabilistic delay), outer lifecycle flaps.
        val faultyA = FaultySeam(mem.host(Pattern("a")), backgroundScope, FaultProfile.Healthy)
        val a = FlakyLifecycleSeam(faultyA, backgroundScope)
        val b = mem.join(InMemoryTag("b"))

        val received = async { b.incoming.take(5).toList() }

        // Send 5 frames, each only while Woven, blipping between them.
        for (i in 1..5) {
            if (a.state.value !is SeamState.Woven) a.recover()
            a.broadcast(byteArrayOf(i.toByte()))
            a.blip(weavingFor = 50.milliseconds)   // flap after each send; recovers itself
        }

        val frames = received.await().map { it.payload.single().toInt() }
        assertEquals(listOf(1, 2, 3, 4, 5), frames, "all Woven-sent frames converge, in order")
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*ResilienceSoakTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/ResilienceSoakTest.kt
git commit --no-gpg-sign -m "test(core): resilience soak — convergence under flap + frame faults"
```

---

### Task 8: Full build + self-check

- [ ] **Step 1: Full multiplatform build**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build`
Expected: BUILD SUCCESSFUL (all targets; `explicitApi` satisfied).

- [ ] **Step 2: Confirm no placeholders shipped**

Run: `grep -rn "TODO(" kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlakyLifecycleSeam.kt kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FlapSchedule.kt`
Expected: no output.

- [ ] **Step 3: Close #65 and #66 via the PRs; record any follow-up** (e.g. the "session reacts to `state == Torn`?" question from Task 6, if it fired).

---

## Self-Review

**Spec coverage:**
- Part A primitive — `FlakyLifecycleSeam` state ownership + Weaving gating (Task 1), `blip`/`flapThenTear` (Task 2), `FlakyLifecycleLoom` (Task 3), `FlapSchedule`+`drive` (Task 4). → issue #65.
- Part B.1 lifecycle contract test → Task 5. Part B.2 session resilience → Task 6. Part B.3 soak → Task 7. → issue #66.
- Composition (lifecycle outer, FaultySeam inner) → exercised in Task 7.
- Part C (composite/Ply resilience, #67) — explicitly out of scope; noted in the header.

**Placeholder scan:** No "TBD"/"implement later"/vague steps. The Task 6 note describes a *conditional concrete action* (`advanceTimeBy(800)`) and a real follow-up to file — not a placeholder.

**Type consistency:** `FlakyLifecycleSeam(delegate, scope)`, `enterWeaving()`/`recover()`/`tear(reason)`/`blip(weavingFor)`/`flapThenTear(flaps, weavingFor, reason)`/`drive(schedule)` used identically across Tasks 1–7. `FlakyLifecycleLoom(delegate, scope).links` used in Tasks 3 and 6. `FlapSchedule(seed, meanUptime, meanDowntime, giveUpAfter)` consistent in Task 4. `SeamRoomFactory(loom, scope, clock, heartbeatConfig)` matches the real signature read from source.
