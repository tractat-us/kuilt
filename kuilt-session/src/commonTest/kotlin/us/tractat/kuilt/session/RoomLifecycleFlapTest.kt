package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.Direction
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.FaultySeam
import us.tractat.kuilt.core.FlakyLifecycleLoom
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Resilience tests for [SeamRoom] backed by a [FlakyLifecycleLoom].
 *
 * Exercises five scenarios:
 * 1. A `Woven → Weaving → Woven` blip — the room survives and delivery resumes.
 * 2. Joiner sees [MembershipEvent.HostLost] **immediately** when the host seam tears
 *    (direct `SeamState.Torn` observation — no heartbeat wait required).
 * 3. Host sees [MembershipEvent.Left] for all admitted peers when its seam tears.
 * 4. Joiner sees [MembershipEvent.HostLost] after the host link goes permanently silent
 *    (heartbeat-timeout path — unchanged from before).
 * 5. Joiner receives `HostLost` and NO spurious `Left` on seam tear (double-event regression).
 *
 * All timing uses virtual time via [runTest] + [advanceTimeBy]. The clock is advanced
 * in lockstep with virtual time so [us.tractat.kuilt.session.partition.HeartbeatPartitionDetector]
 * silence-calculation sees elapsed time correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomLifecycleFlapTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    // Mutable clock advanced in lockstep with advanceTimeBy so the
    // HeartbeatPartitionDetector's silence calculation sees real elapsed time.
    private var clockMs = 0L
    private val clock: () -> Instant get() = { Instant.fromEpochMilliseconds(clockMs) }

    @Test
    fun `room delivers again after the host seam flaps Woven Weaving Woven`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        val hostRoom = factory.host(Pattern("host"))
        val joinerRoom = factory.join(InMemoryTag("joiner"))

        // Both rooms reach a non-empty roster (admit handshake completed).
        hostRoom.roster.first { it.isNotEmpty() }

        // Flap the host's underlying seam (links[0] is the host seam).
        loom.links[0].blip(weavingFor = 150.milliseconds)

        // After recovery, a broadcast still reaches the joiner.
        hostRoom.broadcast(byteArrayOf(42))
        val frame = joinerRoom.incoming.first()
        assertTrue(frame.toByteArray().contentEquals(byteArrayOf(42)), "delivery resumes after the flap")
    }

    /**
     * Verifies [MembershipEvent.HostLost] fires **immediately** when the host seam tears.
     *
     * `SeamState.Torn` on the joiner's seam is a direct terminal signal — the session
     * layer should not wait for heartbeat expiry to emit [MembershipEvent.HostLost].
     * This is faster and more correct than the heartbeat-timeout path.
     */
    @Test
    fun `joiner sees HostLost immediately when the host seam tears`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        val hostRoom = factory.host(Pattern("host"))
        val joinerRoom = factory.join(InMemoryTag("joiner"))
        hostRoom.roster.first { it.isNotEmpty() }

        val hostLostDeferred = async {
            joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
        }

        // Tear the joiner's seam (links[1]) — the transport to the host is permanently gone.
        loom.links[1].tear()

        // HostLost fires without any clock/time advancement — no heartbeat wait.
        val event = hostLostDeferred.await()
        assertIs<MembershipEvent.HostLost>(event)
    }

    /**
     * Verifies the host sees [MembershipEvent.Left] for all admitted joiners when its
     * seam tears.
     *
     * When the host's transport is permanently gone, all admitted joiners are lost.
     * The host should emit [Left] for each of them (mirroring heartbeat-based [PartitionExpired]
     * eviction) rather than silently cancelling.
     */
    @Test
    fun `host sees Left for admitted joiners when host seam tears`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        val hostRoom = factory.host(Pattern("host"))
        factory.join(InMemoryTag("joiner"))
        hostRoom.roster.first { it.isNotEmpty() }

        val leftDeferred = async {
            hostRoom.events.filterIsInstance<MembershipEvent.Left>().first()
        }

        // Tear the host seam — host's transport is gone.
        loom.links[0].tear()

        val leftEvent = leftDeferred.await()
        assertIs<MembershipEvent.Left>(leftEvent)
    }

    /**
     * Verifies [MembershipEvent.HostLost] fires after permanent link silence.
     *
     * Uses [FaultySeam] with [FaultProfile.DropAll] to simulate a dead host link while
     * keeping the peer in the mesh — the heartbeat-timeout escalation path.
     * This path remains valid for failures that don't signal via [SeamState.Torn]
     * (e.g. silent frame drops on an otherwise-alive transport).
     */
    @Test
    fun `joiner sees HostLost when the host link goes permanently silent`() = runTest {
        val innerLoom = InMemoryLoom()

        // Wrap the host seam in FaultySeam so we can drop all frames without removing
        // the host from the mesh.
        val rawHostSeam = innerLoom.host(Pattern("host"))
        val faultyHostSeam = FaultySeam(rawHostSeam, backgroundScope, FaultProfile.Healthy)
        val hostRoom = SeamRoom(
            seam = faultyHostSeam,
            role = SessionRole.Host,
            displayName = "host",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        val joinerRoom = SeamRoom(
            seam = innerLoom.join(InMemoryTag("joiner")),
            role = SessionRole.Joiner,
            displayName = "joiner",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        hostRoom.roster.first { it.isNotEmpty() }

        // Collect HostLost asynchronously before dropping so the event is not missed.
        val hostLostDeferred = async {
            joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
        }

        // Simulate permanent host failure by dropping all frames.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        // Advance virtual time + clock past timeout (200ms) + reconnect window (500ms) + margin.
        repeat(9) {
            clockMs += 100L
            advanceTimeBy(100L)
        }

        val event = hostLostDeferred.await()
        assertIs<MembershipEvent.HostLost>(event)
    }

    /**
     * Regression test for the double-event bug: when the host seam tears, the joiner
     * must receive exactly [MembershipEvent.HostLost] and NO [MembershipEvent.Left].
     *
     * The bug: `tear()` sets `peers = emptySet()` before `state = Torn`, causing
     * [runPeersWatcher] to wake first with `tornHandled == false` and emit a spurious
     * `Left(host, Normal)` — then [runTornWatcher] fired `HostLost`. Result: the joiner
     * received both events (contradictory: `Left(Normal)` reads as "left cleanly").
     *
     * The fix: [runPeersWatcher] reads `seam.state.value is SeamState.Torn` directly
     * (which is already set by the time any collector body resumes) rather than relying
     * on a cross-coroutine flag.
     */
    @Test
    fun `joiner receives HostLost but no Left event when host seam tears`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        val hostRoom = factory.host(Pattern("host"))
        val joinerRoom = factory.join(InMemoryTag("joiner"))
        hostRoom.roster.first { it.isNotEmpty() }

        // Subscribe to HostLost and collect all events BEFORE tear so nothing is missed.
        val hostLostDeferred = async {
            joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
        }
        val events = mutableListOf<MembershipEvent>()
        val collectJob = async {
            joinerRoom.events.collect { events.add(it) }
        }

        // Tear the joiner's seam — transport to host is permanently gone.
        loom.links[1].tear()

        // HostLost fires without any clock advancement — no heartbeat wait.
        val hostLostEvent = hostLostDeferred.await()

        // Drain so any spurious Left (the double-event bug) would appear in events.
        advanceUntilIdle()
        collectJob.cancel()

        assertIs<MembershipEvent.HostLost>(hostLostEvent)
        assertFalse(events.any { it is MembershipEvent.Left }, "spurious Left event in events: $events")
    }
}
