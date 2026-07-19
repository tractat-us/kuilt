# Reconnect state/reason taxonomy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give consumers a reusable classification of *why* a reconnect is in progress or a session terminally failed, threaded onto the two `MembershipEvent`s a reconnect banner keys off.

**Architecture:** Add two public sealed types (`ReconnectReason`, `FailureReason`) to `:kuilt-session`, add a `reason` field to `MembershipEvent.Partitioned` and `MembershipEvent.HostLost`, and populate them at the existing producer sites in `SeamRoom`/`JoinerResumeMachine`. Four of the five producer paths are pure data-flow (no behavior change); the fifth — `FailureReason.Refused(message)` — makes a host `Reject` of a resume authoritative and carries its message, which is a small reconnect-behavior change gated by the full build.

**Tech Stack:** Kotlin Multiplatform (commonMain/commonTest), kotlinx-coroutines, atomicfu locks. Module `:kuilt-session`.

## Global Constraints

- `explicitApi()` is enforced — every public declaration needs an explicit visibility modifier (`public`/`internal`). Copied verbatim from the repo convention.
- Test methods: no `test` prefix (`@Test` suffices); multi-assert tests use `assertAll()`.
- Coroutine determinism: never introduce a production dispatcher in test sources; reuse the existing test harness in `JoinerReconnectTest`/`RoomResumeTest` (`runTest(StandardTestDispatcher(), timeout = …)`), never hand-roll a cluster or `advanceUntilIdle()` on re-arming timers.
- Exception discipline: any new `catch`/best-effort send uses `runCatchingCancellable` (never bare `runCatching`).
- Thread-safety: all new mutable machine state (`refusal`) is guarded by the room's shared reentrant `lock`; suspend calls stay outside the locked section.
- **Verification gate before auto-merge:** Task 3 touches reconnect *behavior* — run the **full `./gradlew build`** plus `./gradlew :examples:test` with `--rerun-tasks`, not a module-scoped build (a `:kuilt-session:build` is a false green for reconnect-behavior changes). Tasks 1–2 and 4 verify with `./gradlew :kuilt-session:build detektAll --rerun-tasks`.
- Docs sync is REQUIRED (Task 4): a new public primitive with no `docs/agent-cookbook.md` row is the exact failure the discovery surface exists to prevent.
- Commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; never the word "chore".

---

### Task 1: Define the taxonomy types + `PartitionEvent.Reason` mapping

**Files:**
- Create: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/ReconnectReason.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/ReconnectReasonTest.kt`

**Interfaces:**
- Consumes: `us.tractat.kuilt.liveness.PartitionEvent.Reason` (existing enum: `Timeout`, `Backpressure`, `TransportClosed`).
- Produces: `public sealed interface ReconnectReason` { `LinkTimeout`, `Backpressure`, `TransportClosed` }; `public sealed interface FailureReason` { `WindowExpired`, `Refused(message: String)`, `Unrecoverable` }; `internal fun PartitionEvent.Reason.toReconnectReason(): ReconnectReason`.

- [ ] **Step 1: Write the failing test**

Create `ReconnectReasonTest.kt`:

```kotlin
package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.PartitionEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectReasonTest {
    @Test
    fun partitionReasonMapsToReconnectReason() = assertAll(
        { assertEquals(ReconnectReason.LinkTimeout, PartitionEvent.Reason.Timeout.toReconnectReason()) },
        { assertEquals(ReconnectReason.Backpressure, PartitionEvent.Reason.Backpressure.toReconnectReason()) },
        { assertEquals(ReconnectReason.TransportClosed, PartitionEvent.Reason.TransportClosed.toReconnectReason()) },
    )
}
```

> `assertAll` is the repo's kotlin.test helper already used across `:kuilt-session` tests — import it the same way sibling tests do (check an existing test's imports; it resolves from the shared test support).

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-session:jvmTest --tests "*ReconnectReasonTest"`
Expected: FAIL — `ReconnectReason` / `toReconnectReason` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `ReconnectReason.kt`:

```kotlin
package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.PartitionEvent

/**
 * Why a peer's link is currently down and a reconnect / grace window is in progress.
 *
 * Attached to [MembershipEvent.Partitioned]. A consumer driving a "reconnecting…" banner
 * uses this to distinguish a silent Wi-Fi drop ([LinkTimeout]) from a peer that stopped
 * reading ([Backpressure]) from a clean transport close ([TransportClosed]).
 *
 * Session-level counterpart of [PartitionEvent.Reason]: the joiner-side [MembershipEvent.Partitioned]
 * (host-tear) does not originate from a [PartitionEvent], so the public session vocabulary owns its
 * own type rather than leaking the lower-level liveness enum.
 */
public sealed interface ReconnectReason {
    /** No heartbeat within `HeartbeatConfig.timeout` — a silent drop. */
    public data object LinkTimeout : ReconnectReason

    /** The per-peer outbound buffer exceeded its configured ceiling. */
    public data object Backpressure : ReconnectReason

    /** The underlying `Seam` was closed or torn. */
    public data object TransportClosed : ReconnectReason
}

/**
 * Why a joiner's session terminally failed. Attached to [MembershipEvent.HostLost].
 *
 * The post-admission analogue of [AdmissionFailure] (which classifies pre-admission
 * failures on [MembershipEvent.AdmissionFailed]).
 */
public sealed interface FailureReason {
    /** The reconnect window elapsed without a successful resume. */
    public data object WindowExpired : FailureReason

    /**
     * The host actively rejected the resume with an `AdmitMessage.Reject`, carrying its raw
     * [message]. kuilt cannot type the host's intent (auth-expired, protocol-mismatch, …) —
     * the admit protocol carries only a free-form string — so those surface here and the
     * consumer parses semantics from [message]. Retrying the same token is futile.
     */
    public data class Refused(public val message: String) : FailureReason

    /** No resume path exists: no reweave support, a non-conforming loom, or no known host. */
    public data object Unrecoverable : FailureReason
}

/** Lift a liveness-layer [PartitionEvent.Reason] to the session-level [ReconnectReason]. */
internal fun PartitionEvent.Reason.toReconnectReason(): ReconnectReason = when (this) {
    PartitionEvent.Reason.Timeout -> ReconnectReason.LinkTimeout
    PartitionEvent.Reason.Backpressure -> ReconnectReason.Backpressure
    PartitionEvent.Reason.TransportClosed -> ReconnectReason.TransportClosed
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*ReconnectReasonTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/ReconnectReason.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/ReconnectReasonTest.kt
git commit --no-gpg-sign -m "feat(session): reconnect ReconnectReason/FailureReason taxonomy (#1556)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Thread `reason` onto the two events + wire the no-behavior-change producers

This task is compile-coupled: adding a required `reason` field to two data classes forces every construction site to supply it, so the field addition and all six construction updates land together. All wiring here is pure data-flow — every branch already knows which case it is in.

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/MembershipEvent.kt:50` and `:90`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (lines ~571, ~575, ~1181–1196, ~1203–1224)
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt` (interface line 70; `runReconnect` lines ~279–352)
- Modify: `kuilt-session-test/src/commonMain/kotlin/us/tractat/kuilt/session/test/FakeRoom.kt` (`partition`, `hostLost` helpers)
- Modify: `kuilt-session-test/src/commonTest/kotlin/us/tractat/kuilt/session/test/FakeRoomTest.kt:137,188`
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt` (~line 280 assertion)
- Modify: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinerReconnectTest.kt` (HostLost assertions at ~211, ~249)

**Interfaces:**
- Consumes: `ReconnectReason`, `FailureReason`, `PartitionEvent.Reason.toReconnectReason()` from Task 1.
- Produces: `MembershipEvent.Partitioned(peerId, at, reason: ReconnectReason)`; `MembershipEvent.HostLost(at, reason: FailureReason)`; `JoinerResumeHost.onReconnectFailed(at: Instant, reason: FailureReason)`.

- [ ] **Step 1: Write the failing tests**

Add to `JoinerReconnectTest.kt` a new test asserting the terminal `reason`, and augment the existing HostLost assertions. New test (mirrors the existing `joiner torn before admit goes straight to HostLost` harness at line 218):

```kotlin
@Test
fun `joiner torn before admit reports HostLost Unrecoverable`() =
    runReconnectTest {  // reuse whatever runTest wrapper the sibling tests use
        // (Copy the exact setup from `joiner torn before admit goes straight to HostLost`:
        //  a joiner SeamRoom with a reweave but torn before it ever admits / mints a token.)
        val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
        // tear the transport with no token held …
        val event = hostLost.await()
        assertEquals(FailureReason.Unrecoverable, event.reason)
    }
```

And update the existing window-timeout test (`joiner falls to HostLost when the base cannot re-weave within the window`, ~line 211) to assert the reason:

```kotlin
val event = hostLost.await()
assertIs<MembershipEvent.HostLost>(event)
assertEquals(FailureReason.WindowExpired, event.reason)
```

Add a Partitioned-reason assertion to a heartbeat-timeout test in `PartitionRoleTest.kt` (host observes a joiner timing out):

```kotlin
val partitioned = hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first()
assertEquals(ReconnectReason.LinkTimeout, partitioned.reason)
```

> Match each new assertion to the concrete harness already in that test file — reuse its `SeamRoom` construction, its `reweave` lambda, and its time-advancement, adding only the `.reason` assertion. Do not stand up a new harness.

- [ ] **Step 2: Run tests to verify they fail (compile error is an acceptable "fail" here)**

Run: `./gradlew :kuilt-session:jvmTest --tests "*JoinerReconnectTest" --tests "*PartitionRoleTest"`
Expected: FAIL to compile — `HostLost` has no `reason`, `Partitioned` has no `reason`.

- [ ] **Step 3: Add the fields to `MembershipEvent`**

`MembershipEvent.kt` line 50:
```kotlin
public data class Partitioned(val peerId: PeerId, val at: Instant, val reason: ReconnectReason) : MembershipEvent
```
line 90:
```kotlin
public data class HostLost(val at: Instant, val reason: FailureReason) : MembershipEvent
```
Extend each KDoc with one line: `[reason]` classifies the cause (link down / terminal failure). Cross-link `FailureReason` ↔ `AdmissionFailure` in the `HostLost` KDoc.

- [ ] **Step 4: Wire the `SeamRoom` producers**

Add imports for `ReconnectReason`, `FailureReason` if the file references them by simple name (same package — no import needed for these two; they live in `us.tractat.kuilt.session`).

`onReconnectStarted` (~line 570) — joiner host-tear is always a transport close:
```kotlin
override fun onReconnectStarted(hostId: PeerId, at: Instant, windowDeadline: Instant) {
    _events.tryEmit(MembershipEvent.Partitioned(hostId, at, ReconnectReason.TransportClosed))
    _events.tryEmit(MembershipEvent.WindowOpened(hostId, windowDeadline))
}
```

`onReconnectFailed` override (~line 575):
```kotlin
override suspend fun onReconnectFailed(at: Instant, reason: FailureReason) = markHostLost(at, reason)
```

`handleUnresponsive` (~line 1181) — map the liveness reason:
```kotlin
} else {
    markPartitioned(event.peerId, event.at, event.reason.toReconnectReason())
}
```

`markPartitioned` (~line 1192):
```kotlin
private fun markPartitioned(peerId: PeerId, at: Instant, reason: ReconnectReason) {
    val updated = lock.withLock { updateMemberLiveness(peerId, Liveness.Partitioned) } ?: return
    _events.tryEmit(MembershipEvent.Partitioned(updated.id, at, reason))
    reconnectController?.onPeerUnresponsive(peerId, at.toEpochMilliseconds())
}
```

`handlePeerLost` (~line 1208) — host peer lost = its window expired:
```kotlin
if (isHostPeer) {
    markHostLost(at, FailureReason.WindowExpired)
} else {
    removeFromRoster(peerId, LeaveReason.PartitionExpired)
}
```

`markHostLost` (~line 1215):
```kotlin
private suspend fun markHostLost(at: Instant, reason: FailureReason) {
    val alreadyLost = lock.withLock { val was = hostLost; hostLost = true; was }
    if (alreadyLost) return
    _events.tryEmit(MembershipEvent.HostLost(at, reason))
    leave(LeaveReason.Error("host lost"))
}
```

- [ ] **Step 5: Wire the `JoinerResumeMachine` no-behavior-change branches**

Add `import us.tractat.kuilt.session.FailureReason` at the top of `JoinerResumeMachine.kt`.

Interface (`JoinerResumeHost`, line 70):
```kotlin
suspend fun onReconnectFailed(at: Instant, reason: FailureReason)
```

`runReconnect` immediate-terminal branch (~line 282):
```kotlin
if (reweaveFn == null || token == null || hostId == null) {
    lock.withLock { reconnectJob = null }
    host.onReconnectFailed(at, FailureReason.Unrecoverable)
    return
}
```

Introduce a `failureReason` local (default `WindowExpired`, so a timeout falls through as window-expired) and set it to `Unrecoverable` on the non-conforming-loom branch; pass it at the failure call site (~lines 297–351):
```kotlin
var failureReason: FailureReason = FailureReason.WindowExpired
val resumed = withTimeoutOrNull(heartbeatConfig.reconnectWindow) {
    var ok = false
    while (!ok) {
        if (host.isTerminal()) return@withTimeoutOrNull false
        val reweaved = runCatchingCancellable { reweaveFn() }
        if (reweaved.isFailure) {
            delay(heartbeatConfig.interval)
            continue
        }
        if (seam.state.value is SeamState.Torn) {
            reweaved.getOrNull()?.takeIf { it !== seam }?.let { throwaway ->
                runCatchingCancellable { throwaway.close() }
            }
            failureReason = FailureReason.Unrecoverable
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
} else if (!lock.withLock { reconnectJob = null; host.isClosed() }) {
    host.onReconnectFailed(clock(), failureReason)
}
```

> `failureReason` is a plain local read/written only on this single reconnect coroutine (the `withTimeoutOrNull` block runs inline on the same coroutine), so no lock is needed for it. Leave every existing lock/critical-section comment intact.

- [ ] **Step 6: Update `FakeRoom` test-support helpers**

`FakeRoom.kt` — give the emit helpers a defaulted `reason` (test ergonomics; the data classes stay required):
```kotlin
public suspend fun partition(peerId: PeerId, at: Instant, reason: ReconnectReason = ReconnectReason.LinkTimeout) {
    updateLiveness(peerId, Liveness.Partitioned)
    eventsChannel.send(MembershipEvent.Partitioned(peerId, at, reason))
}

public suspend fun hostLost(at: Instant, reason: FailureReason = FailureReason.WindowExpired) {
    left = true
    eventsChannel.send(MembershipEvent.HostLost(at, reason))
}
```
Add the two imports (`ReconnectReason`, `FailureReason` — both `us.tractat.kuilt.session`).

- [ ] **Step 7: Update `FakeRoomTest` + `RoomConformanceSuite` assertions**

`FakeRoomTest.kt:137`:
```kotlin
{ assertEquals(MembershipEvent.Partitioned(PeerId("alice"), at, ReconnectReason.LinkTimeout), event) },
```
`FakeRoomTest.kt:188`:
```kotlin
{ assertEquals(MembershipEvent.HostLost(at, FailureReason.WindowExpired), event) },
```
`RoomConformanceSuite.kt` (~line 280) — the suite drives a real heartbeat timeout, so assert the reason:
```kotlin
val partitioned = partitionedDeferred.await()
assertIs<MembershipEvent.Partitioned>(partitioned)
assertEquals(ReconnectReason.LinkTimeout, partitioned.reason)
```
Add the imports where needed.

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :kuilt-session:build :kuilt-session-test:build :kuilt-conformance:build detektAll --rerun-tasks`
Expected: PASS — all variants compile (Android + Native, not just JVM) and every test green.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit --no-gpg-sign -m "feat(session): thread reconnect reason onto Partitioned/HostLost (#1556)

Pure data-flow wiring: no reconnect-behavior change. Unrecoverable vs
WindowExpired distinguished at the existing runReconnect branches.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `FailureReason.Refused(message)` — make a host resume-reject authoritative

Behavior refinement: today a host `Reject` of a resume resolves as `WindowClosed` and `runReconnect` keeps retrying the refused token until the window elapses (→ `WindowExpired`), discarding the host's message. Make the reject authoritative — short-circuit the retry loop and surface `HostLost(Refused(message))`.

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt` (`rejectFlight`, `runReconnect`, new `refusal` field)
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt:862` (pass the reject message)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinerReconnectTest.kt` (new)

**Interfaces:**
- Consumes: `FailureReason.Refused` from Task 1; `AdmitMessage.Reject.reason` (existing `String`).
- Produces: `JoinerResumeMachine.rejectFlight(message: String): Boolean` (signature change).

- [ ] **Step 1: Write the failing test**

Add to `JoinerReconnectTest.kt`, mirroring the existing reconnect harness (the joiner holds a token and a reweave, but the host `Reject`s the resume with a message):

```kotlin
@Test
fun `host reject during resume reports HostLost Refused and does not retry to the window`() =
    // Reuse the resumable-base harness from `joiner auto-resumes …`, but make the host
    // reply AdmitMessage.Reject("auth-expired") to the joiner's Resume instead of ResumeAck.
    runReconnectTest {
        val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
        // tear the transport; joiner re-weaves and sends Resume; host Rejects("auth-expired")
        // Advance time by LESS than the full reconnect window:
        advanceTimeBy(heartbeatConfig.interval)  // one retry interval, not the whole window
        runCurrent()
        val event = hostLost.await()
        assertEquals(FailureReason.Refused("auth-expired"), event.reason)
    }
```

The assertion has two teeth: the reason is `Refused("auth-expired")` (message carried), and `HostLost` fires after ~one interval, proving the loop did **not** spin to the window deadline.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*JoinerReconnectTest.host reject during resume*"`
Expected: FAIL — today the reject retries to the window and yields `WindowExpired`, not `Refused`.

- [ ] **Step 3: Carry the reject message + short-circuit**

`JoinerResumeMachine.kt` — add a lock-guarded field near `pendingResume`:
```kotlin
/**
 * The host's `Reject` message for the in-flight resume, or null. Set by [rejectFlight] when a
 * flight was actually pending; consulted by [runReconnect] to abandon a refused token instead
 * of retrying it to the window deadline. Reset at the start of each [runReconnect] episode.
 */
private var refusal: String? = null
```

`rejectFlight` (line 203) — take the message, record it only when a flight was pending:
```kotlin
fun rejectFlight(message: String): Boolean = lock.withLock {
    val d = pendingResume
    pendingResume = null
    if (d != null) refusal = message
    d?.complete(ResumeResult.WindowClosed)
    d != null
}
```

`runReconnect` — reset `refusal` before the loop (after the immediate-terminal guard), and check it after a non-Success resume:
```kotlin
lock.withLock { refusal = null }
host.silenceHostDetector(hostId)
host.onReconnectStarted(hostId, at, at + heartbeatConfig.reconnectWindow)

var failureReason: FailureReason = FailureReason.WindowExpired
val resumed = withTimeoutOrNull(heartbeatConfig.reconnectWindow) {
    var ok = false
    while (!ok) {
        if (host.isTerminal()) return@withTimeoutOrNull false
        val reweaved = runCatchingCancellable { reweaveFn() }
        if (reweaved.isFailure) { delay(heartbeatConfig.interval); continue }
        if (seam.state.value is SeamState.Torn) {
            reweaved.getOrNull()?.takeIf { it !== seam }?.let { throwaway ->
                runCatchingCancellable { throwaway.close() }
            }
            failureReason = FailureReason.Unrecoverable
            return@withTimeoutOrNull false
        }
        val result = runCatchingCancellable {
            seam.state.first { it is SeamState.Woven }
            host.restartIncomingCollect()
            resume(token)
        }.getOrNull()
        if (result is ResumeResult.Success) {
            ok = true
        } else {
            val refused = lock.withLock { refusal }
            if (refused != null) {
                failureReason = FailureReason.Refused(refused)
                return@withTimeoutOrNull false
            }
            delay(heartbeatConfig.interval)
        }
    }
    true
} ?: false
```
(The `if (resumed) … else if (!isClosed) host.onReconnectFailed(clock(), failureReason)` tail from Task 2 is unchanged.)

Update the `rejectFlight` and `runReconnect` KDoc: a host `Reject` is now authoritative — it abandons the token with `Refused` rather than retrying.

- [ ] **Step 4: Pass the message at the `SeamRoom` call site**

`SeamRoom.kt:862`:
```kotlin
val hadPendingResume = resumeMachine?.rejectFlight(msg.reason) ?: false
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*JoinerReconnectTest*"`
Expected: PASS — the new test and every existing reconnect test green.

- [ ] **Step 6: Full-build gate (reconnect behavior changed)**

Run: `./gradlew build :examples:test detektAll --rerun-tasks`
Expected: BUILD SUCCESSFUL, tasks genuinely `EXECUTED` (not `FROM-CACHE`). A `:kuilt-session`-scoped build is a false green for this change — the full build + `:examples:test` exercises the downstream cluster/runtime E2E stack.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit --no-gpg-sign -m "feat(session): host resume-reject is authoritative, surfaces Refused(message) (#1556)

A host Reject of a resume no longer retries the refused token to the window
deadline; it short-circuits to HostLost(Refused(msg)), carrying the host's
reason. Where auth-expired / protocol-mismatch surface until typed reject codes.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Docs sync — cookbook row, `@sample`, skill

Required by the CLAUDE.md rule the capability-discovery track added: a new public primitive needs a cookbook entry + skill sync + compile-checked sample.

**Files:**
- Modify: `kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt` (new sample)
- Modify: `docs/agent-cookbook.md` (new intent row under **Rejoin & reconnect**)
- Modify: `.claude/skills/kuilt-primitives/SKILL.md` (sync the same primitive)

**Interfaces:**
- Consumes: `MembershipEvent`, `ReconnectReason`, `FailureReason`, `Room` from Tasks 1–2.

- [ ] **Step 1: Add the compile-checked sample**

Append to `AgentCookbookSamples.kt`:
```kotlin
/**
 * Drive a reconnect banner / terminal-error decision from the reason kuilt already classifies,
 * instead of re-deriving your own transient/unrecoverable buckets. [ReconnectReason] says why the
 * link is down while a window is open; [FailureReason] says why the session ended for good.
 */
public suspend fun reconnectBannerSample(room: Room) {
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.Partitioned -> when (event.reason) {
                ReconnectReason.LinkTimeout, ReconnectReason.TransportClosed -> Unit // "Reconnecting…"
                ReconnectReason.Backpressure -> Unit // "Connection congested…"
            }
            is MembershipEvent.HostLost -> when (val reason = event.reason) {
                FailureReason.WindowExpired -> Unit // "Lost the host — rejoin"
                FailureReason.Unrecoverable -> Unit // "Can't reconnect — return to lobby"
                is FailureReason.Refused -> Unit // show reason.message (auth-expired / version, …)
            }
            else -> Unit
        }
    }
}
```
Add imports for `MembershipEvent`, `ReconnectReason`, `FailureReason` if not already present (same package — likely none needed).

- [ ] **Step 2: Verify the sample compiles**

Run: `./gradlew :kuilt-session:compileTestKotlinJvm --rerun-tasks`
Expected: PASS (commonSamples is wired into commonTest).

- [ ] **Step 3: Add the cookbook row + snippet**

`docs/agent-cookbook.md` — add to the "Don't build this yourself" table:
```markdown
| a reconnect banner / "why did we drop" classifier — transient vs. unrecoverable buckets | `MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`) | [Rejoin & reconnect](#rejoin--reconnect) |
```
And under **Rejoin & reconnect**, after the existing back-off snippet, a new intent block quoting the sample verbatim:
```markdown
**Intent:** drive a "reconnecting…" banner, or decide "give up and show an error", from the reason kuilt already observed.
**Primitive:** `MembershipEvent.Partitioned.reason` (`ReconnectReason`) and `HostLost.reason` (`FailureReason`) — don't re-derive your own transient/unrecoverable classification.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#reconnectBannerSample -->
```
(paste the `reconnectBannerSample` body verbatim into the fenced kotlin block.)

- [ ] **Step 4: Sync the skill**

`.claude/skills/kuilt-primitives/SKILL.md` — add the same primitive to whatever list/section mirrors the cookbook (match the file's existing entry format for the reconnect/resume primitive; add the `ReconnectReason`/`FailureReason` classification alongside `ResumeToken`).

- [ ] **Step 5: Verify samples still compile + detekt clean**

Run: `./gradlew :kuilt-session:build detektAll --rerun-tasks`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit --no-gpg-sign -m "docs(session): cookbook + skill entry for reconnect reason taxonomy (#1556)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Two sealed types in `:kuilt-session` → Task 1. ✓
- `Refused(message)` catch-all for auth/protocol → Task 1 (type) + Task 3 (producer). ✓
- Thread onto `Partitioned` + `HostLost` → Task 2. ✓
- Producer wiring table (5 rows) → Task 2 (rows 1–4) + Task 3 (Refused). ✓
- No-behavior-change vs behavior-refinement split with full-build gate → Tasks 2 vs 3. ✓
- Tests through existing harness, no hand-rolled cluster → Tasks 1–3. ✓
- Conformance TCK reason assertion → Task 2 Step 7. ✓
- Docs sync (cookbook + skill + `@sample` + KDoc) → Task 4 (+ KDoc inline in 1–2). ✓
- Out-of-scope items (typed reject codes, folded `ConnectionState`) → not implemented, correctly absent. ✓

**Placeholder scan:** The only non-literal bits are the test harness reuse notes (`runReconnectTest {…}`, "copy the exact setup from …") — these point the implementer at a specific named existing test to mirror rather than invent, which is the correct instruction for augmenting an established coroutine-time harness; the assertions themselves are concrete.

**Type consistency:** `ReconnectReason`/`FailureReason` variants, `toReconnectReason()`, `onReconnectFailed(at, reason)`, `rejectFlight(message)` used identically across tasks. `Refused(message: String)` field named `message` everywhere. ✓
