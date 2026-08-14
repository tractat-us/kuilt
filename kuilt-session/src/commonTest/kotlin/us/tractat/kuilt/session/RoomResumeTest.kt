package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Acceptance tests for Stage 1D: reconnect / resume wired into [Room].
 *
 * All tests use virtual time ([runTest] + [advanceTimeBy]) and an injected clock.
 * [FaultySeam] simulates partition / recovery.
 *
 * **Timing constants** (fast config):
 * - heartbeat interval  = 100 ms
 * - heartbeat timeout   = 200 ms
 * - reconnect window    = 500 ms
 *
 * Advance 4 × 100 ms = 400 ms → PeerUnresponsive fires (within reconnect window).
 * Advance 9 × 100 ms = 900 ms → past reconnect window → PeerLost / HostLost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomResumeTest {
    /** Fast partition-detection timings for deterministic virtual-time tests. */
    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    // ── Test 1: partition fires Partitioned + WindowOpened ────────────────────

    /**
     * Acceptance criterion 1: when the joiner's link becomes unresponsive (from the host's
     * perspective), the host emits [MembershipEvent.Partitioned] (1C) AND
     * [MembershipEvent.WindowOpened] (1D).
     */
    @Test
    fun `host emits WindowOpened after joiner goes unresponsive`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val faultyHostSeam = FaultySeam(loom.host(Pattern("Alice")), backgroundScope, FaultProfile.Healthy)
        val hostRoom = makeSeamRoom(faultyHostSeam, SessionRole.Host, "Alice", clock, RoomId("room-1"))
        makeSeamRoom(loom.join(InMemoryTag("Bob")), SessionRole.Joiner, "Bob", clock)

        hostRoom.roster.first { it.size == 1 }

        val partitioned = async { hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first() }
        val windowOpened = async { hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first() }

        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)

        assertIs<MembershipEvent.Partitioned>(partitioned.await())
        assertIs<MembershipEvent.WindowOpened>(windowOpened.await())
    }

    // ── Test 2: happy-path resume ─────────────────────────────────────────────

    /**
     * Acceptance criterion 2: host link recovers within the window + joiner calls
     * [Room.resume] with valid token → [ResumeResult.Success]; [MembershipEvent.Resumed]
     * fires on both host and joiner.
     */
    @Test
    fun `joiner resume succeeds within reconnect window`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val faultyHostSeam = FaultySeam(loom.host(Pattern("Alice")), backgroundScope, FaultProfile.Healthy)
        val hostRoom = makeSeamRoom(faultyHostSeam, SessionRole.Host, "Alice", clock, RoomId("room-2"))
        val faultyJoinerSeam = FaultySeam(loom.join(InMemoryTag("Bob")), backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(faultyJoinerSeam, SessionRole.Joiner, "Bob", clock)

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }

        val token = joinerRoom.resumeToken
        assertNotNull(token, "joiner must hold a resume token after admit")

        // Partition both links.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))
        faultyJoinerSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        // Advance past timeout only (400 ms < 500 ms reconnect window).
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)

        // Subscribe to Resumed BEFORE healing.
        val hostResumed = async { hostRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }
        val joinerResumed = async { joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }

        // Heal both links, then joiner presents its token.
        faultyHostSeam.heal()
        faultyJoinerSeam.heal()
        advanceTimeBy(50L)

        val result = joinerRoom.resume(token)

        assertIs<ResumeResult.Success>(result)
        assertIs<MembershipEvent.Resumed>(hostResumed.await())
        assertIs<MembershipEvent.Resumed>(joinerResumed.await())
    }

    // ── Test 3: window expiry → HostLost → resume returns WindowClosed ────────

    /**
     * Acceptance criterion 3: host link stays unresponsive past the reconnect window →
     * [MembershipEvent.HostLost] fires; subsequent [Room.resume] returns [ResumeResult.WindowClosed].
     */
    @Test
    fun `host link unresponsive past window fires HostLost and resume returns WindowClosed`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        makeSeamRoom(loom.host(Pattern("Alice")), SessionRole.Host, "Alice", clock, RoomId("room-3"))
        val faultyJoiner = FaultySeam(loom.join(InMemoryTag("Bob")), backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(faultyJoiner, SessionRole.Joiner, "Bob", clock)

        joinerRoom.roster.first { it.isNotEmpty() }
        val token = joinerRoom.resumeToken
        assertNotNull(token, "joiner must have a resume token after admit")

        val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

        faultyJoiner.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        // Advance past timeout (200 ms) + reconnect window (500 ms) with margin.
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)

        assertIs<MembershipEvent.HostLost>(hostLost.await())

        // Room is terminal after HostLost — resume returns WindowClosed immediately.
        val result = joinerRoom.resume(token)
        assertIs<ResumeResult.WindowClosed>(result)
    }

    // ── Test 4a: wrong roomId → Refused(ResumeTokenInvalid) ──────────────────

    /**
     * Acceptance criterion 4a: [Room.resume] with a wrong [RoomId] is refused; no state change.
     *
     * The value used to be [ResumeResult.WindowClosed] — the same value the host returns for a
     * window that genuinely elapsed, and the same one the joiner returns when it never asked at
     * all. Since #2364 it carries the host's own [RejectCode.ResumeTokenInvalid].
     */
    @Test
    fun `resume with wrong roomId is refused as ResumeTokenInvalid`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val hostRoom = makeSeamRoom(loom.host(Pattern("Alice")), SessionRole.Host, "Alice", clock, RoomId("room-abc"))
        val joinerRoom = makeSeamRoom(loom.join(InMemoryTag("Bob")), SessionRole.Joiner, "Bob", clock)

        hostRoom.roster.first { it.size == 1 }

        val badToken = ResumeToken(
            peerId = joinerRoom.selfId,
            roomId = RoomId("room-xyz"), // wrong room
            issuedAt = 0L,
        )

        val result = joinerRoom.resume(badToken)
        assertEquals(
            ResumeResult.Refused("resume-token-invalid: session-mismatch", RejectCode.ResumeTokenInvalid),
            result,
        )
    }

    // ── Test 4b: the joiner gave up first → WindowClosed ─────────────────────

    /**
     * Acceptance criterion 4b: once the joiner's own reconnect has failed, [Room.resume] answers
     * [ResumeResult.WindowClosed] **locally** — it does not ask the host at all.
     *
     * **This is not the host's window expiring, though it was named for it until #2364.** Both
     * directions are dropped for longer than the reconnect window, so the joiner's own host
     * detector fires, its reconnect finds no `reweave` and goes terminal, and the `resume` below
     * short-circuits on `isTerminal()`. While every reject also completed as
     * [ResumeResult.WindowClosed] the two were indistinguishable, so nothing here noticed which
     * one it was measuring. A host that really does refuse an elapsed window now answers
     * `Refused(RejectCode.ResumeWindowExpired)` — see
     * [a joiner can tell an invalid token from a not-yet-open window from an elapsed one], whose
     * third arm drops only the joiner's *outbound* traffic so the joiner stays alive to hear it.
     */
    @Test
    fun `resume after the joiner's own reconnect has failed returns WindowClosed`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val faultyHostSeam = FaultySeam(loom.host(Pattern("Alice")), backgroundScope, FaultProfile.Healthy)
        val hostRoom = makeSeamRoom(faultyHostSeam, SessionRole.Host, "Alice", clock, RoomId("room-4b"))
        val faultyJoiner = FaultySeam(loom.join(InMemoryTag("Bob")), backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(faultyJoiner, SessionRole.Joiner, "Bob", clock)

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }

        val token = joinerRoom.resumeToken
        assertNotNull(token, "joiner must have a resume token after admit")

        // Partition and advance past the full reconnect window.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))
        faultyJoiner.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)

        // Heal (window is already expired on host side).
        faultyHostSeam.heal()
        faultyJoiner.heal()
        advanceTimeBy(50L)

        val result = joinerRoom.resume(token)
        assertIs<ResumeResult.WindowClosed>(result)
    }

    // ── Test 4c: refusals are distinguishable at the Room.resume surface (#2364) ─

    /**
     * **The three ways a host can say no must not arrive as one value.** (#2364)
     *
     * `Room.resume` used to answer every host `Reject` with [ResumeResult.WindowClosed], whatever
     * the host actually said. A consumer handed that value read the cookbook's "grace window
     * elapsed — re-join fresh" and acted on it — even when the real answer was *"that token names
     * a room I don't serve"* (terminal for a different reason) or *"I haven't noticed your drop
     * yet"* (**transient**, and the one case where re-joining fresh is the wrong move). The
     * information existed — `rejectFlight` recorded the [RejectCode] — and was thrown away at the
     * public surface.
     *
     * The three arms are presented to the **same host** so nothing but the host's own reason can
     * separate them:
     * 1. a token minted for another room — terminal, and nothing to do with a window;
     * 2. the genuine token before the host's detector has fired — **transient**, retry;
     * 3. the genuine token once the host's grace window has elapsed — terminal.
     *
     * Arm 3 drops only the joiner's **outbound** traffic: the host's detector stops hearing the
     * joiner (so it opens, then expires, the window) while the joiner keeps hearing the host, so
     * the joiner never goes terminal itself and its `resume` really does reach a live host. A
     * joiner that had gone terminal would answer [ResumeResult.WindowClosed] locally and never ask
     * — which is exactly the vacuity the [ResumeResult.Refused] assertions below exclude.
     */
    @Test
    fun `a joiner can tell an invalid token from a not-yet-open window from an elapsed one`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val hostRoom =
            makeSeamRoom(loom.host(Pattern("Alice")), SessionRole.Host, "Alice", clock, RoomId("room-2364"))
        val faultyJoiner = FaultySeam(loom.join(InMemoryTag("Bob")), backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(faultyJoiner, SessionRole.Joiner, "Bob", clock)

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }

        val token = assertNotNull(joinerRoom.resumeToken, "joiner must hold a resume token after admit")
        val foreign = token.copy(roomId = RoomId("${token.roomId.value}-a-different-room"))

        // (1) Wrong room — the host refuses before it looks at any window state.
        val invalidToken = joinerRoom.resume(foreign)

        // (2) Genuine token, but the host has noticed nothing: no window has ever opened.
        val notYetOpen = joinerRoom.resume(token)

        // (3) Genuine token after the host opened a window and let it elapse.
        faultyJoiner.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))
        repeat(9) {
            clockMs += 100L
            advanceTimeBy(100L)
        }
        faultyJoiner.heal()
        advanceTimeBy(50L)
        val windowElapsed = joinerRoom.resume(token)

        assertAll(
            {
                assertEquals(
                    ResumeResult.Refused("resume-token-invalid: session-mismatch", RejectCode.ResumeTokenInvalid),
                    invalidToken,
                    "a token minted for another room must surface the host's own terminal code",
                )
            },
            {
                assertEquals(
                    ResumeResult.Refused("resume-window-not-yet-open", RejectCode.ResumeWindowNotYetOpen),
                    notYetOpen,
                    "a window the host has not opened yet must surface as RETRYABLE — this is the arm " +
                        "a consumer must not treat as 'grace window elapsed, re-join fresh'",
                )
            },
            {
                assertEquals(
                    ResumeResult.Refused("resume-window-expired", RejectCode.ResumeWindowExpired),
                    windowElapsed,
                    "an elapsed grace window must surface as the host's terminal expiry code, not as " +
                        "the joiner's local WindowClosed (which would mean it never asked)",
                )
            },
            {
                assertNotEquals(
                    invalidToken,
                    notYetOpen,
                    "a token for another room (terminal) and a window that has not opened yet " +
                        "(transient — retry, do NOT re-join fresh) must not be the same value: " +
                        "got $invalidToken for both",
                )
            },
            {
                assertNotEquals(
                    invalidToken,
                    windowElapsed,
                    "an invalid token and an elapsed grace window must not be the same value: " +
                        "got $invalidToken for both",
                )
            },
            {
                assertNotEquals(
                    notYetOpen,
                    windowElapsed,
                    "a window that has not opened yet and one that has elapsed must not be the " +
                        "same value: got $notYetOpen for both",
                )
            },
        )
    }

    // ── Test 5: WindowOpened.expiresAt is Instant (#461) ─────────────────────

    /**
     * Acceptance criterion: [MembershipEvent.WindowOpened.expiresAt] is a [kotlin.time.Instant],
     * not a raw epoch-millis Long. The value must equal the epoch-millis in the internal
     * [us.tractat.kuilt.session.partition.JoinerReconnectEvent.WindowOpened] converted to [Instant].
     */
    @Test
    fun `WindowOpened expiresAt is Instant converted from internal epoch-millis`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val faultyHostSeam = FaultySeam(loom.host(Pattern("Alice")), backgroundScope, FaultProfile.Healthy)
        val hostRoom = makeSeamRoom(faultyHostSeam, SessionRole.Host, "Alice", clock, RoomId("room-461"))
        makeSeamRoom(loom.join(InMemoryTag("Bob")), SessionRole.Joiner, "Bob", clock)

        hostRoom.roster.first { it.size == 1 }

        val windowOpened = async {
            hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()
        }

        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)
        clockMs += 100L; advanceTimeBy(100L)

        val event = windowOpened.await()
        // expiresAt must be an Instant (type assertion via smartcast / member access)
        assertTrue(
            event.expiresAt > Instant.fromEpochMilliseconds(0L),
            "expiresAt must be a non-epoch-zero Instant derived from the internal controller's expiry",
        )
    }

    // ── Test 6: ResumeAck host-authoritative gate + Resumed idempotence (#1618) ─

    /**
     * Regression for #1618: [SeamRoom]'s ResumeAck handler must be
     * host-authoritative and idempotent — mirroring the Farewell gate.
     *
     * A ResumeAck from a **non-host** peer is ignored, and a **duplicate**
     * ResumeAck from the host emits exactly **one** [MembershipEvent.Resumed].
     * The sequence {non-host ack, host ack, duplicate host ack} must therefore
     * yield a single Resumed. Before the fix the handler emitted unconditionally
     * for every ResumeAck, so this sequence produced three.
     */
    @Test
    fun `ResumeAck is host-gated and Resumed is idempotent`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val hostSeam = loom.host(Pattern("Alice"))
        val hostRoom = makeSeamRoom(hostSeam, SessionRole.Host, "Alice", clock, RoomId("room-1618"))
        val joinerDelegate = loom.join(InMemoryTag("Bob"))
        val joinerSeam = FaultySeam(joinerDelegate, backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(joinerSeam, SessionRole.Joiner, "Bob", clock)
        // A third, non-host peer in the mesh whose ResumeAck must be ignored.
        val nonHostSeam = loom.join(InMemoryTag("Carol"))

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() } // host now identified as hostPeerId
        val token = joinerRoom.resumeToken
        assertNotNull(token, "joiner must hold a resume token after admit")

        val resumed = mutableListOf<MembershipEvent.Resumed>()
        backgroundScope.launch {
            joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().collect { resumed.add(it) }
        }
        advanceTimeBy(1L) // let the collector subscribe

        // Drop the joiner's outbound so its Resume never reaches the host: the host
        // never auto-answers, leaving a pending resume flight we resolve by hand.
        joinerSeam.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))
        val resumeResult = async { joinerRoom.resume(token) }
        advanceTimeBy(10L) // resume() installs the pending flight and enters its await

        val ack = AdmitMessage.encode(AdmitMessage.ResumeAck)

        // 1. Non-host peer's ResumeAck — ignored (no Resumed, flight untouched).
        nonHostSeam.sendTo(joinerRoom.selfId, ack)
        advanceTimeBy(10L)

        // 2. Host's ResumeAck — resolves the flight, emits exactly one Resumed.
        hostSeam.sendTo(joinerRoom.selfId, ack)
        advanceTimeBy(10L)

        // 3. Duplicate host ResumeAck — no pending flight, no second Resumed.
        hostSeam.sendTo(joinerRoom.selfId, ack)
        advanceTimeBy(10L)

        assertIs<ResumeResult.Success>(resumeResult.await())
        assertEquals(
            1,
            resumed.size,
            "exactly one Resumed after {non-host, host, duplicate-host} acks",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun TestScope.makeSeamRoom(
        seam: Seam,
        role: SessionRole,
        displayName: String,
        clock: () -> Instant,
        roomId: RoomId? = null,
    ): SeamRoom =
        SeamRoom(
            seam = seam,
            role = role,
            memberName = displayName,
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = roomId,
        ).also { it.start() }
}
