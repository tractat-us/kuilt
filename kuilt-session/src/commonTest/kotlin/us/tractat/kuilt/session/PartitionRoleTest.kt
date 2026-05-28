package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Direction
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.FaultySeam
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.partition.HeartbeatConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Acceptance tests for Stage 1C: role assignment + HostLost terminal state.
 *
 * All tests use virtual time ([runTest] + [advanceTimeBy]) and an injected clock.
 * Partition detection is driven by [FaultySeam] with [FaultProfile.DropAll] to
 * simulate link loss, combined with short [HeartbeatConfig] timeouts so virtual-time
 * advancement is cheap.
 *
 * **HeartbeatConfig timing used here:**
 * - interval = 100 ms
 * - timeout = 200 ms
 * - reconnectWindow = 500 ms
 *
 * A call to `advanceTimeBy(800)` clears the full reconnect window (200 + 500 + margin).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartitionRoleTest {
    /** Fast config for virtual-time tests: all timeouts in the low hundreds of milliseconds. */
    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    private var clockMs = 0L
    private val clock: () -> Instant get() = { Instant.fromEpochMilliseconds(clockMs) }

    // ── Role assignment ────────────────────────────────────────────────────────

    /**
     * Acceptance criterion 1a: [RoomFactory.host] produces a room with [SessionRole.Host].
     */
    @Test
    fun `host factory method assigns Host role`() = runTest {
        val factory = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock, fastConfig)
        val room = factory.host(Pattern("Alice"))
        assertEquals(SessionRole.Host, room.role.value)
        room.leave()
    }

    /**
     * Acceptance criterion 1b: [RoomFactory.join] produces a room with [SessionRole.Joiner].
     */
    @Test
    fun `join factory method assigns Joiner role`() = runTest {
        val loom = InMemoryLoom()
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        factory.host(Pattern("Alice"))
        val joinerRoom = factory.join(InMemoryTag("Bob"))
        assertEquals(SessionRole.Joiner, joinerRoom.role.value)
        joinerRoom.leave()
    }

    // ── HostLost terminal state ────────────────────────────────────────────────

    /**
     * Acceptance criterion 2: when the host's link is partitioned (all frames dropped),
     * the joiner's room receives [MembershipEvent.HostLost] after the reconnect window
     * expires. Subsequent [Room.broadcast] is a silent no-op.
     *
     * **Mechanism:** the joiner's underlying seam is wrapped in a [FaultySeam] with
     * [FaultProfile.DropAll] to simulate total link loss. The fast [HeartbeatConfig]
     * means the window expires after advancing 800 ms of virtual time.
     */
    @Test
    fun `joiner receives HostLost when host link is PeerLost`() = runTest {
        val loom = InMemoryLoom()

        // Host side: plain seam, admitted normally.
        val hostSeam = loom.host(Pattern("Alice"))
        val hostRoom = SeamRoom(
            seam = hostSeam,
            role = SessionRole.Host,
            displayName = "Alice",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        // Joiner side: wrap the joiner's seam in FaultySeam so we can partition it.
        val rawJoinerSeam = loom.join(InMemoryTag("Bob"))
        val faultyJoinerSeam = FaultySeam(rawJoinerSeam, backgroundScope, FaultProfile.Healthy)
        val joinerRoom = SeamRoom(
            seam = faultyJoinerSeam,
            role = SessionRole.Joiner,
            displayName = "Bob",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        // Wait until the handshake completes (host appears in joiner's roster).
        joinerRoom.roster.first { it.isNotEmpty() }

        // Now partition the joiner's link: drop all inbound frames (host's pings won't arrive).
        faultyJoinerSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        // Collect HostLost asynchronously while advancing time.
        val hostLostDeferred = async {
            joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
        }

        // Advance past timeout (200 ms) + reconnect window (500 ms) with margin.
        repeat(9) {
            clockMs += 100L
            advanceTimeBy(100L)
        }

        val hostLost = hostLostDeferred.await()
        assertIs<MembershipEvent.HostLost>(hostLost)

        // After HostLost, broadcast is a silent no-op (does not throw, delivers nothing).
        val broadcastThrew = runCatching { joinerRoom.broadcast("after-host-lost".encodeToByteArray()) }
        assertTrue(broadcastThrew.isSuccess, "broadcast after HostLost must not throw")
    }

    /**
     * Verifies that the host room remains live when a joiner's link is [FaultProfile.DropAll].
     *
     * Acceptance criterion 3: [MembershipEvent.Left] with [LeaveReason.PartitionExpired]
     * fires on the host; the roster shrinks to empty; the host room is not terminal.
     */
    @Test
    fun `host room stays live when joiner link is PeerLost - Left fires with PartitionExpired`() = runTest {
        val loom = InMemoryLoom()

        // Joiner side: normal seam.
        val rawJoinerSeam = loom.join(InMemoryTag("Bob"))

        // Host side: wrap the host's seam so we can drop frames TO the joiner.
        val rawHostSeam = loom.host(Pattern("Alice"))
        val faultyHostSeam = FaultySeam(rawHostSeam, backgroundScope, FaultProfile.Healthy)
        val hostRoom = SeamRoom(
            seam = faultyHostSeam,
            role = SessionRole.Host,
            displayName = "Alice",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        val joinerRoom = SeamRoom(
            seam = rawJoinerSeam,
            role = SessionRole.Joiner,
            displayName = "Bob",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        // Wait for handshake.
        hostRoom.roster.first { it.size == 1 }

        // Partition the host's outbound: joiner never receives pings from host, joiner's
        // pongs never reach the host's detector → host detector times out on the joiner.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        val leftDeferred = async {
            hostRoom.events
                .filterIsInstance<MembershipEvent.Left>()
                .first { it.reason is LeaveReason.PartitionExpired }
        }

        repeat(9) {
            clockMs += 100L
            advanceTimeBy(100L)
        }

        val leftEvent = leftDeferred.await()
        assertAll(
            { assertIs<LeaveReason.PartitionExpired>(leftEvent.reason) },
            { assertEquals(0, hostRoom.roster.value.size) },
        )

        // Host room must remain operational (not terminal — host never loses itself).
        assertFalse(hostRoom.role.value == SessionRole.Joiner, "host role should not change")
        val broadcastSucceeded = runCatching { hostRoom.broadcast("still-alive".encodeToByteArray()) }
        assertTrue(broadcastSucceeded.isSuccess, "host broadcast after joiner-lost must not throw")
    }

    // ── Partitioned / Recovered liveness ──────────────────────────────────────

    /**
     * Acceptance criterion 4a: [PartitionEvent.PeerUnresponsive] drives
     * [MembershipEvent.Partitioned]; the member's [Liveness] transitions to [Liveness.Partitioned].
     *
     * Acceptance criterion 4b: [PartitionEvent.PeerRecovered] drives
     * [MembershipEvent.Recovered]; the member's [Liveness] transitions back to [Liveness.Connected].
     */
    @Test
    fun `Partitioned fires on PeerUnresponsive and Recovered fires on PeerRecovered`() = runTest {
        val loom = InMemoryLoom()

        val rawHostSeam = loom.host(Pattern("Alice"))
        val faultyHostSeam = FaultySeam(rawHostSeam, backgroundScope, FaultProfile.Healthy)
        val hostRoom = SeamRoom(
            seam = faultyHostSeam,
            role = SessionRole.Host,
            displayName = "Alice",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        val joinerRoom = SeamRoom(
            seam = loom.join(InMemoryTag("Bob")),
            role = SessionRole.Joiner,
            displayName = "Bob",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        hostRoom.roster.first { it.size == 1 }
        val joinerPeerId = hostRoom.roster.value.first().id

        // Partition: joiner's link drops all frames → host detector reports PeerUnresponsive.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        val partitionedDeferred = async {
            hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first()
        }

        // Advance past timeout only (not the full reconnect window).
        repeat(4) {
            clockMs += 100L
            advanceTimeBy(100L)
        }

        val partitionedEvent = partitionedDeferred.await()
        assertAll(
            { assertEquals(joinerPeerId, partitionedEvent.peerId) },
            { assertEquals(Liveness.Partitioned, hostRoom.roster.value.first().liveness) },
        )

        // Recover the link and wait for PeerRecovered.
        faultyHostSeam.heal()
        clockMs += 100L
        advanceTimeBy(100L)

        val recoveredDeferred = async {
            hostRoom.events.filterIsInstance<MembershipEvent.Recovered>().first()
        }

        repeat(5) {
            clockMs += 100L
            advanceTimeBy(100L)
        }

        val recoveredEvent = recoveredDeferred.await()
        assertAll(
            { assertEquals(joinerPeerId, recoveredEvent.peerId) },
            { assertEquals(Liveness.Connected, hostRoom.roster.value.first().liveness) },
        )
    }

    /**
     * Verifies roster liveness throughout the Partitioned → Recovered lifecycle
     * independent of the event stream.
     *
     * Collectors for [MembershipEvent.Partitioned] and [MembershipEvent.Recovered] are
     * registered **before** advancing virtual time, so no events are missed.
     */
    @Test
    fun `roster liveness reflects Partitioned and Connected states`() = runTest {
        val loom = InMemoryLoom()

        val rawHostSeam = loom.host(Pattern("Alice"))
        val faultyHostSeam = FaultySeam(rawHostSeam, backgroundScope, FaultProfile.Healthy)
        val hostRoom = SeamRoom(
            seam = faultyHostSeam,
            role = SessionRole.Host,
            displayName = "Alice",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        SeamRoom(
            seam = loom.join(InMemoryTag("Bob")),
            role = SessionRole.Joiner,
            displayName = "Bob",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        hostRoom.roster.first { it.size == 1 }

        // Verify initial liveness is Connected.
        assertEquals(Liveness.Connected, hostRoom.roster.value.first().liveness)

        // Register collector for Partitioned BEFORE partitioning the link.
        val partitionedDeferred = async {
            hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first()
        }

        // Partition link.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        // Advance past timeout → liveness becomes Partitioned.
        repeat(4) {
            clockMs += 100L
            advanceTimeBy(100L)
        }
        partitionedDeferred.await()
        assertEquals(Liveness.Partitioned, hostRoom.roster.value.first().liveness)

        // Register collector for Recovered BEFORE healing the link.
        val recoveredDeferred = async {
            hostRoom.events.filterIsInstance<MembershipEvent.Recovered>().first()
        }

        // Recover → liveness back to Connected.
        faultyHostSeam.heal()
        repeat(8) {
            clockMs += 100L
            advanceTimeBy(100L)
        }
        recoveredDeferred.await()
        assertEquals(Liveness.Connected, hostRoom.roster.value.first().liveness)
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
