@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.identity.test.fakeKeyStore
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
import us.tractat.kuilt.session.partition.ResumeResult
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
    /** The number the next fake device gets: every store made in a test is a new device, so no two
     * rooms share a key unless a test hands one store to both. */
    private var nextDevice = 0

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
     * one it was measuring. A live joiner whose seat the host let go is admitted afresh instead,
     * which [Room.resume] reports as [ResumeResult.WindowClosed] with the room still in session — see
     * [a re-Hello tells a held seat from a gone one], whose
     * second arm drops only the joiner's *outbound* traffic so the joiner stays alive to hear it.
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

    // ── Test 4c: a re-Hello tells a held seat from a gone one (#2364, #3159 Task 1.7) ─

    /**
     * What [Room.resume] reports is the truth about the seat. It says Hello again, and:
     * - while the host still holds the seat — here it has noticed nothing — the `Welcome` is marked
     *   `Restored` and the answer is [ResumeResult.Success];
     * - once the host has opened a window and let it elapse, the seat is gone, so the Hello is a fresh
     *   admission: [ResumeResult.WindowClosed], and the room keeps that admission rather than ending.
     *
     * The second arm drops only the joiner's **outbound** traffic: the host's detector stops hearing
     * the joiner (so it opens, then expires, the window) while the joiner keeps hearing the host, so
     * the joiner never goes terminal itself and its Hello really does reach a live host. A joiner that
     * had gone terminal would answer [ResumeResult.WindowClosed] locally and never ask, which the
     * host's second admission below excludes.
     *
     * Before Task 1.7 the token was presented and these were three refusal codes; the token is not
     * presented any more, so a token for another room says nothing (its host-side check is pinned on a
     * hand-written frame in `ForgedResumeTest`).
     */
    @Test
    fun `a re-Hello tells a held seat from a gone one`() = runTest {
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
        val joined = mutableListOf<MembershipEvent.Joined>()
        backgroundScope.launch { hostRoom.events.filterIsInstance<MembershipEvent.Joined>().collect { joined.add(it) } }
        advanceTimeBy(1L)
        val joinedBefore = joined.size

        // (1) The host has noticed nothing: the seat is held, and connected.
        val held = joinerRoom.resume(token)

        // (2) The host opened a window and let it elapse: the seat is gone.
        faultyJoiner.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))
        repeat(9) {
            clockMs += 100L
            advanceTimeBy(100L)
        }
        faultyJoiner.heal()
        advanceTimeBy(50L)
        val gone = joinerRoom.resume(token)

        assertAll(
            { assertEquals(ResumeResult.Success, held, "a held seat is restored") },
            { assertEquals(ResumeResult.WindowClosed, gone, "a gone seat is a fresh admission") },
            { assertEquals(joinedBefore + 1, joined.size, "the host admitted the joiner afresh, once") },
            { assertEquals(setOf(joinerRoom.selfId), hostRoom.roster.value.map { it.id }.toSet(), "and holds it as its member") },
            { assertTrue(joinerRoom.roster.value.isNotEmpty(), "the joiner's room is still in session") },
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

    // ── Test 6: a ResumeAck closes nothing (#3159, Task 1.7) ──────────────────

    /**
     * Since a reconnect says Hello again, a joiner sends no `Resume`, so a `ResumeAck` answers
     * nothing it asked — from the host or anyone else. Before Task 1.7 the host's ack resolved the
     * flight (#1618 gated it to the host and made `Resumed` idempotent); now the host's own
     * `Welcome` does, and only from a peer whose challenge the joiner answered. Here the joiner's
     * outbound is dropped, so its Hello never reaches the host: every ack is ignored, nothing is
     * resumed, and the flight times out on its own.
     */
    @Test
    fun `a ResumeAck from the host or anyone else closes nothing`() = runTest {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val hostSeam = loom.host(Pattern("Alice"))
        val hostRoom = makeSeamRoom(hostSeam, SessionRole.Host, "Alice", clock, RoomId("room-1618"))
        val joinerDelegate = loom.join(InMemoryTag("Bob"))
        val joinerSeam = FaultySeam(joinerDelegate, backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(joinerSeam, SessionRole.Joiner, "Bob", clock)
        val nonHostSeam = loom.join(InMemoryTag("Carol"))

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }
        val token = assertNotNull(joinerRoom.resumeToken, "joiner must hold a resume token after admit")

        val resumed = mutableListOf<MembershipEvent.Resumed>()
        backgroundScope.launch {
            joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().collect { resumed.add(it) }
        }
        advanceTimeBy(1L)

        joinerSeam.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))
        val resumeResult = async { joinerRoom.resume(token) }
        advanceTimeBy(10L)
        val pending = joinerRoom.hasPendingResume()

        val ack = AdmitMessage.encode(AdmitMessage.ResumeAck)
        nonHostSeam.sendTo(joinerRoom.selfId, ack)
        hostSeam.sendTo(joinerRoom.selfId, ack)
        advanceTimeBy(10L)
        val pendingAfterAcks = joinerRoom.hasPendingResume()

        assertAll(
            { assertTrue(pending, "rig: the Hello's flight was in flight") },
            { assertTrue(pendingAfterAcks, "no ack settles the flight") },
            { assertIs<ResumeResult.TimedOut>(resumeResult.await(), "unanswered, the flight times out") },
            { assertEquals(0, resumed.size, "no ack resumes anything") },
        )
    }
    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun TestScope.makeSeamRoom(
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
            device = RoomDevice.of(fakeKeyStore(nextDevice++)),
            heartbeatConfig = fastConfig,
            roomId = roomId,
        ).also { it.start() }
}
