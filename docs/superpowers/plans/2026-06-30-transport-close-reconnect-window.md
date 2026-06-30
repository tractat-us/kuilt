# Transport-close opens the reconnect window — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a host `Room`, a transport-close drop of an admitted joiner opens the reconnect window (`Partitioned` → `WindowOpened`) instead of emitting `Left(Normal)` immediately, so `Room.resume(token)` within the window succeeds — while a clean `leave()` still emits `Left(Normal)` immediately.

**Architecture:** Make the per-peer `HeartbeatPartitionDetector` the single owner of disconnect by reviving its existing `Reason.TransportClosed` path via a `link.peers` watch; delete the competing `SeamRoom.runPeersWatcher`; map `PeerUnresponsive` on role+reason; add `AdmitMessage.Goodbye` so a deliberate leave is distinguishable from a socket drop.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (virtual-time tests), kotlinx-serialization (CBOR admit frames). Modules: `:kuilt-liveness`, `:kuilt-session`.

## Global Constraints

- `explicitApi()` is enforced — every new public declaration needs an explicit visibility modifier.
- Coroutine test determinism: `runTest(StandardTestDispatcher(), timeout = 5.seconds)` for any multi-coroutine system; bounded `advanceTimeBy` only, **never** `advanceUntilIdle()`; seeded RNG (`Random(seed)`); node/detector coroutines on `backgroundScope`.
- No production dispatchers in test sources (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`).
- Exception discipline: in coroutine contexts use `runCatchingCancellable`, never bare `runCatching`.
- Build/JDK: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` before any gradle command.
- Test method names: no `test` prefix; multi-assert tests use `assertAll()`.
- Verify cache-disabled before merge: `./gradlew :kuilt-session:build :kuilt-liveness:build detektAll --rerun-tasks` (compiles Android + Native variants, not just `jvmTest`).
- Never use the word "chore" in commit messages.

---

### Task 1: Detector watches `link.peers` → immediate `PeerUnresponsive(TransportClosed)`

**Files:**
- Modify: `kuilt-liveness/src/commonMain/kotlin/us/tractat/kuilt/liveness/HeartbeatPartitionDetector.kt`
- Test: `kuilt-liveness/src/commonTest/kotlin/us/tractat/kuilt/liveness/HeartbeatPartitionDetectorTransportCloseTest.kt`

**Interfaces:**
- Consumes: `HeartbeatPartitionDetector(link: Seam, peerId: PeerId, config: HeartbeatConfig, clock: () -> Instant)`; `PartitionEvent.PeerUnresponsive(peerId, at, reason)`; `PartitionEvent.Reason.{Timeout,TransportClosed}`; `Seam.peers: StateFlow<Set<PeerId>>`.
- Produces: no signature change — the detector now emits `PeerUnresponsive(reason = TransportClosed)` the instant `peerId` leaves `link.peers` (after first having been present), in addition to the existing `Timeout` path.

- [ ] **Step 1: Write the failing test**

Create `HeartbeatPartitionDetectorTransportCloseTest.kt`. Uses a controllable fake `Seam` so `peers` can be mutated directly and `incoming` never completes (mirrors the real `PerPeerSeam`). The first test proves the fast-path fires; the second proves a peer that stays in `peers` still takes the slow `Timeout` path (no regression).

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.liveness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HeartbeatPartitionDetectorTransportCloseTest {

    private val config = HeartbeatConfig(
        interval = 5.seconds,
        timeout = 15.seconds,
        reconnectWindow = 60.seconds,
    )

    /** A minimal controllable [Seam]: `peers` is mutable, `incoming` never completes. */
    private class FakeLink(
        override val selfId: PeerId,
        private val target: PeerId,
    ) : Seam {
        private val _peers = MutableStateFlow(setOf(selfId, target))
        override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()
        private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> = _state.asStateFlow()
        private val _incoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 64)
        override val incoming: Flow<Swatch> = _incoming.asSharedFlow()
        override suspend fun broadcast(payload: ByteArray) = Unit
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        override suspend fun close(reason: CloseReason) = Unit
        fun dropTarget() = _peers.update { it - target }
    }

    @Test
    fun `target leaving peers fires PeerUnresponsive TransportClosed immediately`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val self = PeerId("self")
            val target = PeerId("target")
            val link = FakeLink(self, target)
            val detector = HeartbeatPartitionDetector(link, target, config, { Instant.fromEpochMilliseconds(0L) })

            detector.start(backgroundScope)
            // Let the detector observe the target as present, then drop it.
            advanceTimeBy(config.interval.inWholeMilliseconds)
            link.dropTarget()

            val event = detector.events.first()
            assertEquals(target, event.peerId)
            val unresponsive = event as PartitionEvent.PeerUnresponsive
            assertEquals(PartitionEvent.Reason.TransportClosed, unresponsive.reason)
        }

    @Test
    fun `peer that stays in peers still uses the Timeout path`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val self = PeerId("self")
            val target = PeerId("target")
            val link = FakeLink(self, target) // never drops target
            var nowMs = 0L
            val detector = HeartbeatPartitionDetector(link, target, config, { Instant.fromEpochMilliseconds(nowMs) })

            detector.start(backgroundScope)
            // No pong ever arrives; advance past the timeout.
            nowMs = config.timeout.inWholeMilliseconds + config.interval.inWholeMilliseconds
            advanceTimeBy(nowMs)

            val event = detector.events.first()
            val unresponsive = event as PartitionEvent.PeerUnresponsive
            assertEquals(PartitionEvent.Reason.Timeout, unresponsive.reason)
        }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-liveness:jvmTest --tests "*TransportCloseTest*"`
Expected: the first test FAILS — `detector.events.first()` hangs/times out (5 s) because nothing fires when the peer leaves `peers`. (The second test already passes via the existing Timeout path.)

- [ ] **Step 3: Implement the peers-watch in the heartbeat loop**

In `HeartbeatPartitionDetector.kt`, add two imports after the existing `kotlinx.coroutines` imports:

```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
```

Replace the entire `runHeartbeatLoop()` function (currently lines ~107–128) with this version. It tracks whether the target was ever present, wakes early when the target leaves the set, and falls back to the existing interval tick + timeout logic otherwise:

```kotlin
    private suspend fun runHeartbeatLoop() {
        var observedPresent = false
        while (true) {
            if (!observedPresent && peerId in link.peers.value) observedPresent = true

            // Wait up to one interval, but wake immediately if the target leaves the
            // peer set after having been seen — a definitive transport close.
            withTimeoutOrNull(config.interval.inWholeMilliseconds) {
                link.peers.first { observedPresent && peerId !in it }
            }
            if (!observedPresent && peerId in link.peers.value) observedPresent = true

            if (observedPresent && peerId !in link.peers.value) {
                emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), PartitionEvent.Reason.TransportClosed))
                val recovered = awaitRecoveryOrLoss()
                if (!recovered) return
                continue
            }

            if (backpressurePending) {
                backpressurePending = false
                emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), PartitionEvent.Reason.Backpressure))
                val recovered = awaitRecoveryOrLoss()
                if (!recovered) return
                continue
            }

            sendPing()

            val silenceMs = clock().toEpochMilliseconds() - lastSeenEpochMs
            if (silenceMs >= config.timeout.inWholeMilliseconds) {
                emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), PartitionEvent.Reason.Timeout))
                val recovered = awaitRecoveryOrLoss()
                if (!recovered) return
            }
        }
    }
```

(The `delay(config.interval)` at the old loop top is replaced by the `withTimeoutOrNull(...)` wait, which serves the same pacing role while also reacting to peer-set changes. `awaitRecoveryOrLoss()` is unchanged; on recovery the peer is present again so `observedPresent` stays true and the next leave re-arms.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :kuilt-liveness:jvmTest --tests "*TransportCloseTest*"`
Expected: PASS (both tests).

- [ ] **Step 5: Run the full liveness suite (no regression)**

Run: `./gradlew :kuilt-liveness:jvmTest`
Expected: PASS — existing `HeartbeatPartitionDetectorTest` (Timeout / Backpressure / recovery paths) unaffected.

- [ ] **Step 6: Commit**

```bash
git add kuilt-liveness/src/commonMain/kotlin/us/tractat/kuilt/liveness/HeartbeatPartitionDetector.kt \
        kuilt-liveness/src/commonTest/kotlin/us/tractat/kuilt/liveness/HeartbeatPartitionDetectorTransportCloseTest.kt
git commit --no-gpg-sign -m "feat(liveness): detector fires TransportClosed when target leaves link.peers (#993)"
```

---

### Task 2: Host transport-close opens the window; delete `runPeersWatcher`; role+reason mapping

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/TransportCloseWindowTest.kt`

**Interfaces:**
- Consumes (from Task 1): the detector now emits `PeerUnresponsive(reason = TransportClosed)` on a hard drop. Also: `MuxServerLoom`, `RoomHubSeam` (`:kuilt-core`), `InMemoryConnectionSource`, `connectionPair` (`:kuilt-test`), `meshSeam`, `NamedMux` (`:kuilt-core`), `PartitionEvent.Reason` (`:kuilt-liveness`).
- Produces: on a host `SeamRoom`, a transport-close of an admitted joiner now emits `MembershipEvent.Partitioned` then `MembershipEvent.WindowOpened` (not `Left(Normal)`); on a joiner, a host transport-close emits `MembershipEvent.HostLost`. New private `handleUnresponsive(event: PartitionEvent.PeerUnresponsive)`.

- [ ] **Step 1: Write the failing acceptance test**

Create `TransportCloseWindowTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TransportCloseWindowTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    @Test
    fun `host opens reconnect window on joiner transport close`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

            val source = InMemoryConnectionSource()
            val serverLoom = MuxServerLoom(
                source = source,
                scope = backgroundScope,
                selfId = PeerId("server"),
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = dispatcher,
                random = Random(13L),
            )
            val hostSeam = serverLoom.host(Pattern("table-7"))
            val hostRoom = SeamRoom(
                seam = hostSeam,
                role = SessionRole.Host,
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val clientMesh = meshSeam(PeerId("client"), listOf(clientConn), dispatcher, Random(1L))
            val clientMux = NamedMux(clientMesh, backgroundScope)
            SeamRoom(
                seam = clientMux.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }

            val partitioned = async { hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first() }
            val windowOpened = async { hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first() }

            // Transport close — the in-memory analog of a socket close.
            clientMesh.close()

            assertIs<MembershipEvent.Partitioned>(partitioned.await())
            assertIs<MembershipEvent.WindowOpened>(windowOpened.await())
        }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*TransportCloseWindowTest*"`
Expected: FAIL — the `windowOpened`/`partitioned` awaits never complete (5 s timeout). With the current `runPeersWatcher`, the host emits `Left(Normal)` instead.

- [ ] **Step 3: Replace the partition-event mapping with a role+reason `handleUnresponsive`**

In `SeamRoom.kt`, replace `handlePartitionEvent` (currently ~lines 745–751):

```kotlin
    private suspend fun handlePartitionEvent(event: PartitionEvent) {
        when (event) {
            is PartitionEvent.PeerUnresponsive -> markPartitioned(event.peerId, event.at)
            is PartitionEvent.PeerRecovered -> markRecovered(event.peerId, event.at)
            is PartitionEvent.PeerLost -> handlePeerLost(event.peerId, event.at)
        }
    }
```

with:

```kotlin
    private suspend fun handlePartitionEvent(event: PartitionEvent) {
        when (event) {
            is PartitionEvent.PeerUnresponsive -> handleUnresponsive(event)
            is PartitionEvent.PeerRecovered -> markRecovered(event.peerId, event.at)
            is PartitionEvent.PeerLost -> handlePeerLost(event.peerId, event.at)
        }
    }

    /**
     * Maps a [PartitionEvent.PeerUnresponsive] to a membership event by role + reason.
     *
     * A joiner whose **host** is lost to a definitive transport close goes terminal
     * immediately ([markHostLost]) — there is no host-resume path, so holding a window is
     * pointless delay. Every other case (host watching a joiner; a joiner's non-host peer;
     * a silent [PartitionEvent.Reason.Timeout] partition that may still recover) opens the
     * reconnect window via [markPartitioned].
     */
    private suspend fun handleUnresponsive(event: PartitionEvent.PeerUnresponsive) {
        val hostTransportClose = lock.withLock {
            _role.value == SessionRole.Joiner && event.peerId == hostPeerId
        } && event.reason == PartitionEvent.Reason.TransportClosed
        if (hostTransportClose) {
            markHostLost(event.at)
        } else {
            markPartitioned(event.peerId, event.at)
        }
    }
```

- [ ] **Step 4: Delete `runPeersWatcher`**

In `start()` (currently ~lines 321–331), remove the `runPeersWatcher` launch so the list reads:

```kotlin
    internal fun start() {
        val jobs = mutableListOf(
            scope.launch { runMainLoop() },
            scope.launch { runTornWatcher() },
        )
        if (reconnectController != null) {
            jobs += scope.launch { runReconnectEventLoop(reconnectController) }
        }
        loopJobs = jobs
    }
```

Then delete the entire `runPeersWatcher()` function (the `// ── Peers watcher: detect disconnects ──` block, currently ~lines 333–358). Leave `runTornWatcher` and its `// Defers to [runTornWatcher]` semantics intact. Update the `runTornWatcher` KDoc lines that reference `runPeersWatcher` suppression so they no longer mention a peers watcher (in the `handleTorn`/`runTornWatcher` doc block, drop the sentence "`[runPeersWatcher]` suppresses its own emissions when torn …").

Also update the class-level KDoc (the "Two coroutines run per room" / "Peers watcher" bullet, ~lines 132–141) to remove the peers-watcher bullet, since the detector now owns transport-close detection.

- [ ] **Step 5: Run the acceptance test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*TransportCloseWindowTest*"`
Expected: PASS.

- [ ] **Step 6: Run the full session suite (no regression)**

Run: `./gradlew :kuilt-session:jvmTest`
Expected: PASS. In particular `RoomResumeTest` (silent partition via `FaultySeam.DropAll` → peer stays in `peers` → Timeout path → window) and `RoomLifecycleFlapTest` (host `Left` via the `Torn` path; joiner `HostLost` with NO `Left`) stay green. `SeamRoomTest."Left event fires when admitted member leaves"` is expected to FAIL at this step — it asserts `Left(Normal)` on `joinerRoom.leave()`, which Task 3 restores via the `Goodbye` path. Note it and proceed; do not weaken the assertion.

- [ ] **Step 7: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/TransportCloseWindowTest.kt
git commit --no-gpg-sign -m "feat(session): host transport-close opens reconnect window; detector owns disconnect (#993)"
```

---

### Task 3: `AdmitMessage.Goodbye` — graceful leave stays immediate `Left(Normal)`

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/AdmitMessage.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/GracefulLeaveTest.kt`

**Interfaces:**
- Consumes: `AdmitMessage.encode`, `SeamRoom.leave(reason: LeaveReason)`, `removeFromRoster(peerId, reason)`, `stopDetector(peerId)`.
- Produces: new public `AdmitMessage.Goodbye` (`data object`, `@SerialName("goodbye")`); host-side handling that emits `Left(Normal)` immediately on receipt; joiner `leave(Normal)` broadcasts `Goodbye` before closing.

- [ ] **Step 1: Write the failing test**

Create `GracefulLeaveTest.kt`. Asserts a clean joiner `leave()` produces exactly one host `Left(Normal)` and NO `WindowOpened`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GracefulLeaveTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    @Test
    fun `clean joiner leave emits Left Normal immediately with no window`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

            val source = InMemoryConnectionSource()
            val serverLoom = MuxServerLoom(
                source = source,
                scope = backgroundScope,
                selfId = PeerId("server"),
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = dispatcher,
                random = Random(13L),
            )
            val hostSeam = serverLoom.host(Pattern("table-7"))
            val hostRoom = SeamRoom(
                seam = hostSeam,
                role = SessionRole.Host,
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val clientMesh = meshSeam(PeerId("client"), listOf(clientConn), dispatcher, Random(1L))
            val clientMux = NamedMux(clientMesh, backgroundScope)
            val joinerRoom = SeamRoom(
                seam = clientMux.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }

            val left = async { hostRoom.events.filterIsInstance<MembershipEvent.Left>().first() }

            joinerRoom.leave()

            val leftEvent = left.await()
            assertEquals(LeaveReason.Normal, leftEvent.reason)
            // The roster empties and no window was opened.
            hostRoom.roster.first { it.isEmpty() }
        }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*GracefulLeaveTest*"`
Expected: FAIL — without `Goodbye`, `joinerRoom.leave()` closes the seam, the detector fires `TransportClosed`, and the host emits `Partitioned`/`WindowOpened` rather than `Left(Normal)`; the `Left` await times out.

- [ ] **Step 3: Add `AdmitMessage.Goodbye`**

In `AdmitMessage.kt`, add this variant after `ResumeAck` (before the `companion object`):

```kotlin
    /**
     * Sent by a joiner to the host on a deliberate [us.tractat.kuilt.session.Room.leave].
     *
     * Distinguishes a graceful leave from a bare transport close: on receipt the host
     * evicts the member immediately with [us.tractat.kuilt.session.LeaveReason.Normal],
     * rather than opening a reconnect window. Best-effort — a lost Goodbye degrades to the
     * transport-close → reconnect-window path.
     */
    @Serializable
    @SerialName("goodbye")
    public data object Goodbye : AdmitMessage
```

- [ ] **Step 4: Handle `Goodbye` on the host and announce it on leave**

In `SeamRoom.kt` `handleAdmitFrame` (the `when (val msg = AdmitMessage.decode(bytes))` block), add a branch after the `ResumeAck` case:

```kotlin
            is AdmitMessage.Goodbye -> {
                if (_role.value == SessionRole.Host) {
                    lock.withLock { stopDetector(sender) }
                    removeFromRoster(sender, LeaveReason.Normal)
                }
            }
```

Then replace `leave(reason)` (currently ~lines 923–939) so a joiner's `Normal` leave announces `Goodbye` before tearing the seam:

```kotlin
    override suspend fun leave(reason: LeaveReason) {
        // Flip closed + snapshot jobs under lock; announce, cancel, and close outside.
        val plan = lock.withLock {
            if (closed) return
            closed = true
            Triple(
                _role.value == SessionRole.Joiner && reason is LeaveReason.Normal,
                loopJobs,
                detectorJobs.values.toList().also { detectorJobs.clear() },
            )
        }
        val (announce, jobsToCancel, detectorJobsToCancel) = plan
        // Announce a graceful leave on the still-live seam before tearing it down, so the
        // host evicts with Normal rather than treating the close as a transport drop.
        if (announce) {
            runCatchingCancellable { seam.broadcast(AdmitMessage.encode(AdmitMessage.Goodbye)) }
        }
        jobsToCancel.forEach { it.cancel() }
        detectorJobsToCancel.forEach { it.cancel() }
        seam.close(
            when (reason) {
                is LeaveReason.Normal -> CloseReason.Normal
                is LeaveReason.Error -> CloseReason.Error(RuntimeException(reason.message))
                is LeaveReason.PartitionExpired -> CloseReason.Normal
            },
        )
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*GracefulLeaveTest*"`
Expected: PASS.

- [ ] **Step 6: Re-run the test that regressed in Task 2**

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamRoomTest*"`
Expected: PASS — `Left event fires when admitted member leaves` is now satisfied via the `Goodbye` path (`joinerRoom.leave()` over `InMemoryLoom` broadcasts `Goodbye`, host emits `Left(Normal)`).

- [ ] **Step 7: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/AdmitMessage.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/GracefulLeaveTest.kt
git commit --no-gpg-sign -m "feat(session): AdmitMessage.Goodbye keeps graceful leave immediate Left(Normal) (#993)"
```

---

### Task 4: End-to-end proof — resume within window succeeds; window expiry evicts

**Files:**
- Modify: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/TransportCloseWindowTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–3; `Room.resume(token)`, `ResumeResult.Success`, `MembershipEvent.Resumed`, `MembershipEvent.Left`, `LeaveReason.PartitionExpired`, `Room.resumeToken`.
- Produces: two acceptance tests closing the loop on #993 — the fast-resume succeeds, and an un-resumed window expires to `Left(PartitionExpired)`.

- [ ] **Step 1: Write the failing tests**

Append two tests to `TransportCloseWindowTest.kt` (inside the class). The first reconnects a fresh client with the **same** `PeerId` (server re-associates by id), captures the joiner token before the drop, and resumes within the window. The second advances past the window and asserts eviction.

```kotlin
    @Test
    fun `joiner resumes within the window after transport close`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }

            val source = InMemoryConnectionSource()
            val serverLoom = MuxServerLoom(
                source = source,
                scope = backgroundScope,
                selfId = PeerId("server"),
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = dispatcher,
                random = Random(13L),
            )
            val hostSeam = serverLoom.host(Pattern("table-7"))
            val hostRoom = SeamRoom(
                seam = hostSeam,
                role = SessionRole.Host,
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val client = PeerId("client")
            val (serverConn1, clientConn1) = connectionPair()
            source.offer(serverConn1)
            val clientMesh1 = meshSeam(client, listOf(clientConn1), dispatcher, Random(1L))
            val clientMux1 = NamedMux(clientMesh1, backgroundScope)
            val joinerRoom1 = SeamRoom(
                seam = clientMux1.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }
            joinerRoom1.roster.first { it.isNotEmpty() }
            val token = joinerRoom1.resumeToken!!

            // Drop, then reconnect a fresh transport with the SAME PeerId.
            clientMesh1.close()
            hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()

            val (serverConn2, clientConn2) = connectionPair()
            source.offer(serverConn2)
            val clientMesh2 = meshSeam(client, listOf(clientConn2), dispatcher, Random(2L))
            val clientMux2 = NamedMux(clientMesh2, backgroundScope)
            val joinerRoom2 = SeamRoom(
                seam = clientMux2.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            val hostResumed = async { hostRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }

            val result = joinerRoom2.resume(token)

            assertIs<us.tractat.kuilt.session.partition.ResumeResult.Success>(result)
            assertIs<MembershipEvent.Resumed>(hostResumed.await())
        }

    @Test
    fun `window expires to Left PartitionExpired when no resume`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }

            val source = InMemoryConnectionSource()
            val serverLoom = MuxServerLoom(
                source = source,
                scope = backgroundScope,
                selfId = PeerId("server"),
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = dispatcher,
                random = Random(13L),
            )
            val hostSeam = serverLoom.host(Pattern("table-7"))
            val hostRoom = SeamRoom(
                seam = hostSeam,
                role = SessionRole.Host,
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val clientMesh = meshSeam(PeerId("client"), listOf(clientConn), dispatcher, Random(1L))
            val clientMux = NamedMux(clientMesh, backgroundScope)
            SeamRoom(
                seam = clientMux.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }

            val left = async {
                hostRoom.events.filterIsInstance<MembershipEvent.Left>().first()
            }

            clientMesh.close()
            hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()

            // Advance past the 500 ms reconnect window (with margin).
            repeat(8) { clockMs += 100L; advanceTimeBy(100L) }

            assertEquals(LeaveReason.PartitionExpired, left.await().reason)
        }
```

Add the import `import us.tractat.kuilt.session.partition.ResumeResult` is referenced fully-qualified above, so no new import line is strictly required; if preferred, add `import us.tractat.kuilt.session.partition.ResumeResult` and shorten the assertion.

- [ ] **Step 2: Run the tests to verify they pass**

Run: `./gradlew :kuilt-session:jvmTest --tests "*TransportCloseWindowTest*"`
Expected: PASS (all three tests in the file). If `joiner resumes…` flakes on handshake ordering, await `joinerRoom2.roster.first { it.isNotEmpty() }` before calling `resume(token)`; do not add arbitrary delays.

- [ ] **Step 3: Commit**

```bash
git add kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/TransportCloseWindowTest.kt
git commit --no-gpg-sign -m "test(session): resume-within-window + window-expiry eviction over MuxServerLoom (#993)"
```

---

### Task 5: Full cross-variant verification + PR

**Files:** none (verification + PR).

- [ ] **Step 1: Full build with cache disabled (Android + Native variants, real detekt)**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-liveness:build :kuilt-session:build detektAll --rerun-tasks`
Expected: BUILD SUCCESSFUL; the relevant test-compile and test tasks show `EXECUTED` (not `FROM-CACHE`). If any test-compile task still shows `FROM-CACHE`, re-run adding `--no-build-cache`.

- [ ] **Step 2: Confirm the repro is gone end-to-end**

Run: `./gradlew :kuilt-session:jvmTest --tests "*TransportCloseWindowTest*" --tests "*GracefulLeaveTest*" --tests "*RoomResumeTest*" --tests "*SeamRoomTest*" --tests "*RoomLifecycleFlapTest*"`
Expected: PASS for all.

- [ ] **Step 3: Open the PR**

```bash
git fetch origin main
git push -u origin HEAD
gh pr create --title "fix(session): transport-close opens the reconnect window (#993)" \
  --body "$(cat <<'EOF'
> 🤖 This PR was generated by Claude on behalf of @keddie.

Closes #993.

A host Room over a real fabric emitted `Left(Normal)` the instant an admitted joiner's
socket closed, so the reconnect window never opened and `Room.resume(token)` always returned
`WindowClosed`. The detector now owns disconnect: a hard drop fires `PeerUnresponsive(TransportClosed)`
(via a `link.peers` watch reviving the already-documented path), the competing `runPeersWatcher`
is deleted, `PeerUnresponsive` is mapped on role + reason, and a new `AdmitMessage.Goodbye` keeps a
deliberate `leave()` as an immediate `Left(Normal)`.

Design: `docs/superpowers/specs/2026-06-30-transport-close-reconnect-window-design.md`.

Unblocks the consumer-side fast-resume driver that previously fell back to a cold re-attach.
EOF
)"
gh pr merge --auto --squash
```

- [ ] **Step 4: Open the PR in the browser**

```bash
gh pr view --web
```

---

## Self-Review

**Spec coverage:**
- §1 detector peers-watch → Task 1. ✓
- §2 role+reason mapping + delete `runPeersWatcher` → Task 2. ✓
- §3 `AdmitMessage.Goodbye` → Task 3. ✓
- Testing (acceptance, Goodbye, joiner-side, detector unit, regression) → Tasks 1–4 + Task 5 cross-variant. ✓ (Joiner-side host `TransportClosed → HostLost` is implemented in Task 2 Step 3 and exercised by the existing `RoomLifecycleFlapTest` "HostLost and NO Left" assertion verified in Task 2 Step 6 / Task 5 Step 2.)
- Scope & risks (modules, explicitApi, double-event, cache-disabled, downstream) → Global Constraints + Task 5. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. ✓

**Type consistency:** `handleUnresponsive(event: PartitionEvent.PeerUnresponsive)`, `markPartitioned`, `markHostLost`, `removeFromRoster(peerId, LeaveReason.Normal)`, `stopDetector(peerId)`, `AdmitMessage.Goodbye`, `Triple(...)` destructured into `(announce, jobsToCancel, detectorJobsToCancel)` — names consistent across tasks and match the current source read during planning. ✓
