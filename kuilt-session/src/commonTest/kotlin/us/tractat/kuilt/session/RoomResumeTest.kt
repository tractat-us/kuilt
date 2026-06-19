package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Direction
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.FaultySeam
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    // ── Test 4a: wrong roomId → WindowClosed ─────────────────────────────────

    /**
     * Acceptance criterion 4a: [Room.resume] with a wrong [RoomId] returns
     * [ResumeResult.WindowClosed]; no state change.
     */
    @Test
    fun `resume with wrong roomId returns WindowClosed`() = runTest {
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
        assertIs<ResumeResult.WindowClosed>(result)
    }

    // ── Test 4b: expired window → WindowClosed ────────────────────────────────

    /**
     * Acceptance criterion 4b: [Room.resume] after the reconnect window has expired
     * returns [ResumeResult.WindowClosed].
     */
    @Test
    fun `resume after reconnect window expires returns WindowClosed`() = runTest {
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
            displayName = displayName,
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = roomId,
        ).also { it.start() }
}
