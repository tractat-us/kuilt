# SeamRoom Resume-Machine Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the joiner-side reconnect/resume state machine out of `SeamRoom` (kuilt-session) into a new `JoinerResumeMachine`, shrinking `SeamRoom.kt` from 1230 lines with zero behavioral change.

**Architecture:** A new `internal class JoinerResumeMachine` (package `us.tractat.kuilt.session.partition`, alongside the existing host-side `DefaultJoinerReconnectController`) owns `resumeToken`, the pending-resume deferred, the reconnect guard/job, and the reweave-retry loop. It shares `SeamRoom`'s existing `reentrantLock` instance (passed in) rather than owning an independent lock, and reaches back into `SeamRoom` through a small `JoinerResumeHost` callback interface that `SeamRoom` implements directly.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, kotlinx.atomicfu (`reentrantLock`), kotlin.test.

## Global Constraints

- **Existing tests are inviolable.** `RoomConformanceSuite`, `JoinerReconnectTest`, `RoomResumeTest`, and `TransportCloseWindowTest` must end this plan byte-for-byte unmodified (verify with `git diff --stat` against each — zero output). If a step seems to require editing one of them, stop and reconsider the step; do not edit the test.
- **No behavioral change.** This is a structural extraction. Every callback in the design is invoked while `JoinerResumeMachine` already holds the shared lock — the same "callers must hold lock" convention `SeamRoom` already documents on `startDetector`/`stopDetector`.
- **`explicitApi()` is enforced** repo-wide, but every new declaration in this plan is `internal`, so no explicit-API annotations are required on them.
- **Full build, not just `jvmTest`, before considering any task done** — `jvmTest` doesn't compile the Android variant or Kotlin/Native targets, and this module has multiplatform (`all`) targets.
- Full design rationale: `docs/superpowers/specs/2026-07-03-seamroom-resume-machine-extraction-design.md`. Tracks [#1122](https://github.com/tractat-us/kuilt/issues/1122).
- **Openness to revision:** if you find a way to make this safer, simpler, or more maintainable — a different boundary, a smaller callback surface, a clearer name — make the change. The two exceptions are the two constraints above (existing-tests-inviolable, joiner-side-only scope) — come back and discuss before changing those.

---

### Task 1: Create `JoinerResumeMachine` + isolated unit tests

**Files:**
- Create: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt`
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachineTest.kt`

**Interfaces:**
- Consumes: `us.tractat.kuilt.core.PeerId`, `Seam`, `SeamState`, `runCatchingCancellable` (all in `us.tractat.kuilt.core`); `us.tractat.kuilt.liveness.HeartbeatConfig`; `us.tractat.kuilt.session.MembershipEvent`; `us.tractat.kuilt.session.admit.AdmitMessage` (its `Resume` variant: `AdmitMessage.Resume(tokenPeerId: String, tokenRoomId: String, issuedAt: Long)`, and `AdmitMessage.encode(AdmitMessage): ByteArray`); `us.tractat.kuilt.session.partition.ResumeToken`, `RoomId`, `ResumeResult` (already in the same package, no import needed).
- Produces (for Task 2): `internal interface JoinerResumeHost` with methods `currentHostPeerId(): PeerId?`, `isTerminal(): Boolean`, `silenceHostDetector(hostId: PeerId)`, `restoreHostDetector(hostId: PeerId)`, `restartIncomingCollect()`, `suspend fun onReconnectFailed(at: Instant)`, `emit(event: MembershipEvent)`. And `internal class JoinerResumeMachine(selfId: PeerId, seam: Seam, clock: () -> Instant, heartbeatConfig: HeartbeatConfig, reweave: (suspend () -> Seam)?, scope: CoroutineScope, lock: ReentrantLock, host: JoinerResumeHost)` with public members `val resumeToken: ResumeToken?`, `fun mintTokenIfAbsent(roomId: String?)`, `suspend fun resume(token: ResumeToken): ResumeResult`, `fun completeResume(result: ResumeResult)`, `fun attemptReconnect(at: Instant)`, `fun reconnectJobSnapshot(): Job?`.

- [ ] **Step 1: Write the new file's failing/pending tests first**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachineTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.test.FakeSeam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private class FakeResumeHost(
    private val hostId: PeerId? = PeerId("host"),
    private val terminal: Boolean = false,
) : JoinerResumeHost {
    var silenceCalls = 0
        private set
    var restartCalls = 0
        private set
    var reconnectFailedCalls = 0
        private set
    val emitted = mutableListOf<MembershipEvent>()

    override fun currentHostPeerId(): PeerId? = hostId
    override fun isTerminal(): Boolean = terminal
    override fun silenceHostDetector(hostId: PeerId) { silenceCalls++ }
    override fun restoreHostDetector(hostId: PeerId) { /* no-op for these tests */ }
    override fun restartIncomingCollect() { restartCalls++ }
    override suspend fun onReconnectFailed(at: Instant) { reconnectFailedCalls++ }
    override fun emit(event: MembershipEvent) { emitted += event }
}

private fun TestScope.machine(
    seam: FakeSeam = FakeSeam(),
    host: FakeResumeHost = FakeResumeHost(),
    reweave: (suspend () -> Seam)? = { seam },
    scope: CoroutineScope = backgroundScope,
): JoinerResumeMachine = JoinerResumeMachine(
    selfId = PeerId("self"),
    seam = seam,
    clock = { Instant.fromEpochMilliseconds(0) },
    heartbeatConfig = HeartbeatConfig(
        interval = 50.milliseconds,
        timeout = 100.milliseconds,
        reconnectWindow = 200.milliseconds,
    ),
    reweave = reweave,
    scope = scope,
    lock = reentrantLock(),
    host = host,
)

class JoinerResumeMachineTest {
    @Test
    fun mintTokenIfAbsentIsIdempotentAndIgnoresNullRoomId() = runTest {
        val machine = machine()
        machine.mintTokenIfAbsent(null)
        assertNull(machine.resumeToken)

        machine.mintTokenIfAbsent("room-1")
        val first = assertNotNull(machine.resumeToken)
        assertEquals(RoomId("room-1"), first.roomId)

        machine.mintTokenIfAbsent("room-2")
        assertEquals(first, machine.resumeToken, "a second mint must not overwrite the first token")
    }

    @Test
    fun resumeReturnsWindowClosedWhenTerminal() = runTest {
        val seam = FakeSeam()
        val machine = machine(seam = seam, host = FakeResumeHost(terminal = true))
        val token = ResumeToken(PeerId("self"), RoomId("room-1"), issuedAt = 0)

        assertEquals(ResumeResult.WindowClosed, machine.resume(token))
        assertEquals(0, seam.broadcasts.size, "must not send Resume once the room is terminal")
    }

    @Test
    fun resumeSendsFrameAndCompletesOnAck() = runTest {
        val seam = FakeSeam()
        val machine = machine(seam = seam)
        val token = ResumeToken(PeerId("self"), RoomId("room-1"), issuedAt = 0)

        val resultDeferred = async { machine.resume(token) }
        runCurrent()
        assertEquals(1, seam.broadcasts.size, "resume() must broadcast one AdmitMessage.Resume frame")

        machine.completeResume(ResumeResult.Success)
        assertEquals(ResumeResult.Success, resultDeferred.await())
    }

    @Test
    fun attemptReconnectNoOpsWithoutKnownHostPeer() = runTest {
        val host = FakeResumeHost(hostId = null)
        val machine = machine(host = host)

        machine.attemptReconnect(Instant.fromEpochMilliseconds(0))
        runCurrent()

        assertEquals(1, host.reconnectFailedCalls, "a missing hostId must fail the reconnect, not silently no-op")
        assertNull(machine.reconnectJobSnapshot())
    }

    @Test
    fun attemptReconnectGuardsAgainstDoubleLaunch() = runTest {
        var reweaveCalls = 0
        val gate = CompletableDeferred<Unit>()
        val seam = FakeSeam()
        val machine = machine(
            seam = seam,
            reweave = {
                reweaveCalls++
                gate.await()
                seam
            },
        )
        machine.mintTokenIfAbsent("room-1")

        machine.attemptReconnect(Instant.fromEpochMilliseconds(0))
        runCurrent()
        assertEquals(1, reweaveCalls)

        machine.attemptReconnect(Instant.fromEpochMilliseconds(0))
        runCurrent()
        assertEquals(1, reweaveCalls, "a second attempt while one is in flight must not re-enter reweave")
    }
}
```

- [ ] **Step 2: Run the new test file to confirm it fails to compile (the production class doesn't exist yet)**

Run: `./gradlew :kuilt-session:jvmTest --tests "us.tractat.kuilt.session.partition.JoinerResumeMachineTest"`
Expected: **compilation failure** — `unresolved reference: JoinerResumeMachine` (and `JoinerResumeHost`).

- [ ] **Step 3: Write `JoinerResumeMachine.kt`**

Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt`:

```kotlin
package us.tractat.kuilt.session.partition

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.admit.AdmitMessage
import kotlin.time.Instant

/**
 * Services [JoinerResumeMachine] needs from the owning
 * [us.tractat.kuilt.session.SeamRoom] that it cannot perform itself.
 *
 * Every method is called while [JoinerResumeMachine] already holds its shared
 * lock — implementations may assume the lock is held, exactly like
 * [us.tractat.kuilt.session.SeamRoom]'s existing `startDetector`/`stopDetector`
 * convention ("callers must hold lock").
 */
internal interface JoinerResumeHost {
    /** The host's [PeerId] as tracked by the room, or null if not yet known. */
    fun currentHostPeerId(): PeerId?

    /** True if the room is already closed or has declared the host lost. */
    fun isTerminal(): Boolean

    /** Stop the per-peer liveness detector for [hostId] for the reconnect's duration. */
    fun silenceHostDetector(hostId: PeerId)

    /** Restart the per-peer liveness detector for [hostId] after a successful resume. */
    fun restoreHostDetector(hostId: PeerId)

    /** (Re)start the room's single `Seam.incoming` collector on the current (possibly healed) generation. */
    fun restartIncomingCollect()

    /** The reconnect window elapsed (or the loom is non-conforming) without a successful resume. */
    suspend fun onReconnectFailed(at: Instant)

    /** Emit a [MembershipEvent] on the room's event stream. */
    fun emit(event: MembershipEvent)
}

/**
 * Owns the **joiner-side** reconnect/resume state machine extracted out of
 * [us.tractat.kuilt.session.SeamRoom] (#1122): resume-token issuance, the
 * pending-resume deferred, the single-in-flight-reconnect guard, and the
 * reweave/resume retry loop.
 *
 * The host-side counterpart is [DefaultJoinerReconnectController] — this type
 * is its joiner-side sibling, constructed only for [us.tractat.kuilt.session.SessionRole.Joiner]
 * rooms, exactly as [DefaultJoinerReconnectController] is constructed only for
 * [us.tractat.kuilt.session.SessionRole.Host] ones.
 *
 * **Lock model:** shares the *same* [lock] instance as the owning `SeamRoom`
 * rather than an independent one, so the atomic guard-flip-and-store
 * invariants the original code relied on (flip [reconnecting] and store
 * [reconnectJob] in one critical section, so a concurrent `leave()` can never
 * observe the guard set with no job to cancel) carry over unchanged. As with
 * `SeamRoom`, suspend calls are never made while holding [lock].
 */
internal class JoinerResumeMachine(
    private val selfId: PeerId,
    private val seam: Seam,
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig,
    private val reweave: (suspend () -> Seam)?,
    private val scope: CoroutineScope,
    private val lock: ReentrantLock,
    private val host: JoinerResumeHost,
) {
    /**
     * The joiner's reconnect credential, minted via [mintTokenIfAbsent] once the
     * host's `Welcome` carries a `roomId`. Mirrors [us.tractat.kuilt.session.Room.resumeToken].
     */
    var resumeToken: ResumeToken? = null
        private set

    /** Pending [resume] call awaiting the host's `ResumeAck`/`Reject`. Guarded by [lock]. */
    private var pendingResume: CompletableDeferred<ResumeResult>? = null

    /** Guards the single in-flight reconnect attempt. Guarded by [lock]. */
    private var reconnecting = false

    /** The child job running the in-flight [runHostReconnect], if any. Guarded by [lock]. */
    private var reconnectJob: Job? = null

    /** Mints [resumeToken] if not yet set and [roomId] is non-null. Idempotent. */
    fun mintTokenIfAbsent(roomId: String?) {
        lock.withLock {
            if (resumeToken == null && roomId != null) {
                resumeToken = ResumeToken(
                    peerId = selfId,
                    roomId = RoomId(roomId),
                    issuedAt = clock().toEpochMilliseconds(),
                )
            }
        }
    }

    /**
     * Attempt to resume from [token]. Sends `AdmitMessage.Resume` and awaits the host's reply
     * via [completeResume]. Returns [ResumeResult.WindowClosed] immediately if the room is
     * already terminal, or if the send itself fails (a genuine [kotlinx.coroutines.CancellationException]
     * propagates uncaught).
     */
    suspend fun resume(token: ResumeToken): ResumeResult {
        val deferred = lock.withLock {
            if (host.isTerminal()) return ResumeResult.WindowClosed
            CompletableDeferred<ResumeResult>().also { pendingResume = it }
        }

        val resumeMsg = AdmitMessage.encode(
            AdmitMessage.Resume(
                tokenPeerId = token.peerId.value,
                tokenRoomId = token.roomId.value,
                issuedAt = token.issuedAt,
            ),
        )
        val sendResult = runCatchingCancellable { seam.broadcast(resumeMsg) }
        if (sendResult.isFailure) {
            lock.withLock { pendingResume = null }
            return ResumeResult.WindowClosed
        }

        return deferred.await()
    }

    /** Resolves the pending [resume] call, if any, with [result]. */
    fun completeResume(result: ResumeResult) {
        val deferred = lock.withLock {
            val d = pendingResume
            pendingResume = null
            d
        }
        deferred?.complete(result)
    }

    /** The in-flight reconnect job, if any. For [us.tractat.kuilt.session.SeamRoom.leave]'s cancel snapshot. */
    fun reconnectJobSnapshot(): Job? = lock.withLock { reconnectJob }

    /**
     * Claim the single in-flight reconnect and drive it on [scope]. A no-op if the room is
     * already terminal or a reconnect is already running — see [reconnecting].
     */
    fun attemptReconnect(at: Instant) {
        lock.withLock {
            when {
                host.isTerminal() -> return
                reconnecting -> return
                else -> {
                    reconnecting = true
                    reconnectJob = scope.launch { runHostReconnect(at) }
                }
            }
        }
    }

    /**
     * Attempt to keep the session alive across a host transport tear: re-weave, wait for
     * [SeamState.Woven], restart the incoming collector, and [resume]. Falls to
     * [JoinerResumeHost.onReconnectFailed] on timeout, a non-conforming loom, or a missing
     * token/hostId/reweave.
     */
    private suspend fun runHostReconnect(at: Instant) {
        val reweaveFn = reweave
        val (token, hostId) = lock.withLock { resumeToken to host.currentHostPeerId() }
        if (reweaveFn == null || token == null || hostId == null) {
            lock.withLock { reconnectJob = null }
            host.onReconnectFailed(at)
            return
        }

        lock.withLock { host.silenceHostDetector(hostId) }

        host.emit(MembershipEvent.Partitioned(hostId, at))
        host.emit(MembershipEvent.WindowOpened(hostId, at + heartbeatConfig.reconnectWindow))

        val resumed = withTimeoutOrNull(heartbeatConfig.reconnectWindow) {
            var ok = false
            while (!ok) {
                if (lock.withLock { host.isTerminal() }) return@withTimeoutOrNull false
                val reweaved = runCatchingCancellable { reweaveFn() }
                if (reweaved.isFailure) {
                    delay(heartbeatConfig.interval)
                    continue
                }
                if (seam.state.value is SeamState.Torn) {
                    reweaved.getOrNull()?.takeIf { it !== seam }?.let { throwaway ->
                        runCatchingCancellable { throwaway.close() }
                    }
                    return@withTimeoutOrNull false
                }
                val result = runCatchingCancellable {
                    seam.state.first { it is SeamState.Woven }
                    host.restartIncomingCollect()
                    resume(token)
                }.getOrNull()
                if (result is ResumeResult.Success) ok = true else delay(heartbeatConfig.interval)
            }
            true
        } ?: false

        if (resumed) {
            lock.withLock {
                reconnecting = false
                reconnectJob = null
                host.restoreHostDetector(hostId)
            }
        } else if (!lock.withLock { reconnectJob = null; host.isTerminal() }) {
            host.onReconnectFailed(clock())
        }
    }
}
```

- [ ] **Step 4: Run the new tests to verify they pass**

Run: `./gradlew :kuilt-session:jvmTest --tests "us.tractat.kuilt.session.partition.JoinerResumeMachineTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Run detekt on the module**

Run: `./gradlew :kuilt-session:detektAll --rerun-tasks`
Expected: PASS, no new findings.

- [ ] **Step 6: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachineTest.kt
git commit -m "feat(session): extract joiner-side JoinerResumeMachine (#1122)

Standalone, not yet wired into SeamRoom — the next commit delegates to it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Wire `JoinerResumeMachine` into `SeamRoom`

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt`

**Interfaces:**
- Consumes: `JoinerResumeMachine`, `JoinerResumeHost` from Task 1 (exact signatures above).
- Produces: no new public surface — `Room.resumeToken` and `Room.resume(token)` keep their existing signatures; `SeamRoom` additionally implements `JoinerResumeHost` (internal, invisible to consumers).

- [ ] **Step 1: Add the import and make `SeamRoom` implement `JoinerResumeHost`**

In `SeamRoom.kt`, add to the import block (after the existing `us.tractat.kuilt.session.partition.*` imports around line 35-40):

```kotlin
import us.tractat.kuilt.session.partition.JoinerResumeHost
import us.tractat.kuilt.session.partition.JoinerResumeMachine
```

Change the class declaration (currently `internal class SeamRoom(...) : Room {` around line 183/221):

```kotlin
internal class SeamRoom(
    // ... existing constructor parameters unchanged ...
) : Room, JoinerResumeHost {
```

- [ ] **Step 2: Replace the "Reconnect / resume state" joiner fields with the machine**

Replace the entire block from the `// ── Reconnect / resume state ───` section header through the `pendingResume` field declaration (lines 343–389 in the pre-change file — everything from the section comment down through `private var pendingResume: CompletableDeferred<ResumeResult>? = null`, but **keep** the `reconnectController` field, since that's host-side and out of scope) with:

```kotlin
    // ── Reconnect / resume state ───────────────────────────────────────────────

    /**
     * **Host only.** Manages per-joiner reconnect windows.
     *
     * Null when this room's [role] is [SessionRole.Joiner] — the host doesn't
     * reconnect to itself, and the joiner doesn't manage windows for others.
     *
     * Constructed lazily at room start so the scope and clock are guaranteed ready.
     */
    private val reconnectController: JoinerReconnectController? =
        if (role == SessionRole.Host && roomId != null) {
            DefaultJoinerReconnectController(
                roomId = roomId,
                reconnectWindowMs = heartbeatConfig.reconnectWindow.inWholeMilliseconds,
                clock = { clock().toEpochMilliseconds() },
                scope = scope,
            )
        } else {
            null
        }

    /**
     * **Joiner only.** Owns resume-token issuance, the pending-resume deferred, the
     * reconnect guard, and the reweave/resume retry loop (#1122). Null for hosts —
     * mirrors [reconnectController] being null for joiners.
     */
    private val resumeMachine: JoinerResumeMachine? =
        if (role == SessionRole.Joiner) {
            JoinerResumeMachine(
                selfId = selfId,
                seam = seam,
                clock = clock,
                heartbeatConfig = heartbeatConfig,
                reweave = reweave,
                scope = scope,
                lock = lock,
                host = this,
            )
        } else {
            null
        }

    override val resumeToken: ResumeToken?
        get() = resumeMachine?.resumeToken
```

Note: `override var resumeToken: ResumeToken? = null private set` no longer exists as a field — it's now the `get()` above. Remove the old property declaration (it was directly above the old "Pending resume calls" KDoc in the original, around lines 368–379) as part of this same replacement.

Also delete the old `pendingResume` field and its KDoc (originally lines 381–388) — it's now inside `JoinerResumeMachine`.

- [ ] **Step 3: Implement the remaining `JoinerResumeHost` methods**

Add these methods to `SeamRoom` (a natural spot is right after `stopDetector` / `hasDetector`, around the existing partition-detection section, since they all touch `detectorJobs`):

```kotlin
    // ── JoinerResumeHost ──────────────────────────────────────────────────────

    override fun currentHostPeerId(): PeerId? = hostPeerId

    override fun isTerminal(): Boolean = lock.withLock { closed || hostLost }

    override fun silenceHostDetector(hostId: PeerId) {
        stopDetector(hostId)
    }

    override fun restoreHostDetector(hostId: PeerId) {
        admittedById[hostId]?.let { startDetector(it) }
    }

    override fun emit(event: MembershipEvent) {
        _events.tryEmit(event)
    }

    override suspend fun onReconnectFailed(at: Instant) {
        markHostLost(at)
    }
```

`restartIncomingCollect()` needs no new code — it already exists on `SeamRoom` (used by `runMainLoop`); just add `override` to its existing declaration:

```kotlin
    override fun restartIncomingCollect() {
```

(was `private fun restartIncomingCollect() {`).

- [ ] **Step 4: Delete `attemptHostReconnect` and `runHostReconnect`**

Delete both methods entirely (originally lines 448–566: from `private fun attemptHostReconnect(at: Instant) {` through the closing brace of `runHostReconnect`). Their logic now lives in `JoinerResumeMachine`.

- [ ] **Step 5: Update `runJoinerTornWatcher` and `handleUnresponsive` to call the machine**

In `runJoinerTornWatcher` (originally lines 434–437):

```kotlin
    private suspend fun runJoinerTornWatcher() {
        seam.state.filterIsInstance<SeamState.Torn>().first()
        resumeMachine?.attemptReconnect(clock())
    }
```

In `handleUnresponsive` (originally lines 981–990), replace the `attemptHostReconnect(event.at)` call:

```kotlin
        if (hostTransportClose) {
            resumeMachine?.attemptReconnect(event.at)
        } else {
            markPartitioned(event.peerId, event.at)
        }
```

- [ ] **Step 6: Update `handleWelcome`'s token minting**

Replace both `mintResumeTokenIfAbsent(welcome.roomId)` call sites inside `handleWelcome` (originally lines 854 and 864) with:

```kotlin
            resumeMachine?.mintTokenIfAbsent(welcome.roomId)
```

Then delete the now-unused `mintResumeTokenIfAbsent` private function entirely (originally lines 880–888).

- [ ] **Step 7: Update `handleResumeAck` and the `Reject` branch**

In `handleResumeAck` (originally lines 897–906), replace the `pendingResume` field access:

```kotlin
    private fun handleResumeAck(sender: PeerId) {
        lock.withLock { updateMemberLiveness(sender, Liveness.Connected) }
        _events.tryEmit(MembershipEvent.Resumed(selfId))
        resumeMachine?.completeResume(ResumeResult.Success)
    }
```

In `handleAdmitFrame`'s `AdmitMessage.Reject` branch (originally lines 713–719):

```kotlin
            is AdmitMessage.Reject -> {
                if (_role.value == SessionRole.Joiner) {
                    resumeMachine?.completeResume(ResumeResult.WindowClosed)
                }
            }
```

- [ ] **Step 8: Update `resume()` override**

Replace the entire `override suspend fun resume(token: ResumeToken): ResumeResult { ... }` body (originally lines 1145–1170):

```kotlin
    override suspend fun resume(token: ResumeToken): ResumeResult {
        return resumeMachine?.resume(token) ?: ResumeResult.WindowClosed
    }
```

- [ ] **Step 9: Fold the reconnect job into `leave()`'s cancel snapshot**

In `leave()` (originally lines 1172–1198), the `plan` computation currently is:

```kotlin
            Triple(
                _role.value == SessionRole.Joiner && reason is LeaveReason.Normal,
                loopJobs + listOfNotNull(incomingCollectJob, reconnectJob),
                detectorJobs.values.toList().also { detectorJobs.clear() },
            )
```

Change the middle element to:

```kotlin
                loopJobs + listOfNotNull(incomingCollectJob, resumeMachine?.reconnectJobSnapshot()),
```

- [ ] **Step 10: Update the class-level "Thread safety" KDoc**

The class-level KDoc block (originally lines 167–171) currently reads:

```
 * **Thread safety**: all mutable membership state (`admittedById`, `closed`, `hostLost`,
 * `hostPeerId`, `pendingResume`, `resumeToken`, `reconnecting`, `incomingCollectJob`,
 * `detectorJobs`, `channelViews`) is guarded by an atomicfu [reentrantLock]. Critical sections
```

Update the field list to drop the fields that moved out and note the shared machine:

```
 * **Thread safety**: all mutable membership state (`admittedById`, `closed`, `hostLost`,
 * `hostPeerId`, `incomingCollectJob`, `detectorJobs`, `channelViews`) is guarded by an
 * atomicfu [reentrantLock]. [resumeMachine]'s own state (`resumeToken`, `pendingResume`,
 * `reconnecting`, `reconnectJob`) is guarded by the *same* lock instance, passed in at
 * construction (#1122) — see [us.tractat.kuilt.session.partition.JoinerResumeMachine]'s
 * KDoc for why a shared lock rather than an independent one. Critical sections
```

- [ ] **Step 11: Confirm existing tests are byte-for-byte unmodified**

Run: `git diff --stat -- kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinerReconnectTest.kt kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomResumeTest.kt kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/TransportCloseWindowTest.kt kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt`
Expected: **no output** (zero diff). If anything shows up, revert that file and find a different way to satisfy the step that caused the edit.

- [ ] **Step 12: Run the full existing reconnect/resume/conformance test suite**

Run: `./gradlew :kuilt-session:jvmTest --tests "us.tractat.kuilt.session.JoinerReconnectTest" --tests "us.tractat.kuilt.session.RoomResumeTest" --tests "us.tractat.kuilt.session.TransportCloseWindowTest" --tests "us.tractat.kuilt.session.partition.JoinerResumeMachineTest"`
Expected: PASS, all tests green, unmodified.

Then also run the conformance suite instantiations that cover `SeamRoom` (check `kuilt-session/src/commonTest` for a class extending `RoomConformanceSuite`, e.g. a `SeamRoomConformanceTest`) with the same `--tests` pattern.

- [ ] **Step 13: Run detekt on the module**

Run: `./gradlew :kuilt-session:detektAll --rerun-tasks`
Expected: PASS, no new findings.

- [ ] **Step 14: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt
git commit -m "refactor(session): delegate joiner reconnect/resume to JoinerResumeMachine (#1122)

SeamRoom implements JoinerResumeHost and delegates resumeToken/resume()/
handleResumeAck/reconnect-entry to the machine from the previous commit.
No existing test touched; RoomConformanceSuite + JoinerReconnectTest +
RoomResumeTest + TransportCloseWindowTest all green, unmodified.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Full verification and PR

**Files:** none (verification + PR only).

**Interfaces:** none.

- [ ] **Step 1: Full cache-disabled build**

Run: `./gradlew :kuilt-session:build :kuilt-conformance:build detektAll --rerun-tasks`
Expected: BUILD SUCCESSFUL, all tasks `EXECUTED` (not `FROM-CACHE`, not `NO-SOURCE`).

- [ ] **Step 2: Full repo build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. This catches Android-variant and Kotlin/Native compile issues that `jvmTest` alone misses.

- [ ] **Step 3: Re-confirm the existing-tests-inviolable constraint on the whole branch**

Run: `git diff origin/main --stat -- kuilt-session/src/commonTest kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt | grep -v JoinerResumeMachineTest`
Expected: **no output** — the only test-tree diff on the whole branch is the new `JoinerResumeMachineTest.kt` file from Task 1.

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin refactor/1122-resume-machine-extraction
gh pr create --title "refactor(session): extract joiner-side resume state machine (#1122)" --body "$(cat <<'EOF'
🤖 This comment was generated by Claude on behalf of @keddie.

## Summary
- Extracts the joiner-side reconnect/resume state machine out of `SeamRoom`
  into a new `JoinerResumeMachine`, mirroring the existing host-side
  `DefaultJoinerReconnectController`.
- Pure structural extraction — no behavioral change. `SeamRoom.kt` shrinks
  from 1230 lines.
- Design: `docs/superpowers/specs/2026-07-03-seamroom-resume-machine-extraction-design.md`
- Plan: `docs/superpowers/plans/2026-07-03-seamroom-resume-machine-extraction.md`

Closes #1122

## Test plan
- [x] New `JoinerResumeMachineTest` (isolated, fake host/seam)
- [x] `JoinerReconnectTest` / `RoomResumeTest` / `TransportCloseWindowTest` / `RoomConformanceSuite` — unmodified, all green
- [x] Full `./gradlew build` green
EOF
)"
```

- [ ] **Step 5: Open the PR in the browser**

Run: `gh pr view --web`
