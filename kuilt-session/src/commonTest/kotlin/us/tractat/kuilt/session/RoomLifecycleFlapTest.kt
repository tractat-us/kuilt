package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.FlakyLifecycleLoom
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.composite.CompositeLoom
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
        assertTrue(frame.payload.contentEquals(byteArrayOf(42)), "delivery resumes after the flap")
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
            memberName = "host",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        val joinerRoom = SeamRoom(
            seam = innerLoom.join(InMemoryTag("joiner")),
            role = SessionRole.Joiner,
            memberName = "joiner",
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
     * The bug: `tear()` sets `peers = emptySet()` before `state = Torn`, causing the
     * peers-change path to wake before the torn state was visible and emit a spurious
     * `Left(host, Normal)` — then [runTornWatcher] fired `HostLost`. Result: the joiner
     * received both events (contradictory: `Left(Normal)` reads as "left cleanly").
     *
     * The fix: Torn-vs-peers suppression is owned solely by [runTornWatcher], which reads
     * `seam.state.value is SeamState.Torn` directly (already set by the time any collector
     * body resumes) rather than relying on a cross-coroutine flag.
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

    /**
     * Payoff regression for #1367: a [SeamRoom] over a multipath composite must NOT evict its
     * members when the composite transiently loses **every** ply. Under the old rollup the
     * all-plies-torn composite published a derived terminal [SeamState.Torn], which tripped the
     * host's Torn-watcher and permanently tore a recoverable room. Now the composite reports
     * [SeamState.Weaving] on all-plies-torn, so the Torn-watcher correctly does not fire and the
     * roster survives the degrade.
     */
    @Test
    fun `host over a composite does not evict when the composite transiently goes all-plies-torn`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val plyScope = CoroutineScope(dispatcher + SupervisorJob())
        val plyALoom = FlakyLifecycleLoom(InMemoryLoom(), plyScope)
        val plyBLoom = FlakyLifecycleLoom(InMemoryLoom(), plyScope)
        val compositeLoom = CompositeLoom(
            listOf(PlyId("a") to plyALoom, PlyId("b") to plyBLoom),
            dispatcher,
        )
        // Build the rooms directly so we can hold the composite host seam and assert its rollup state.
        val hostSeam = compositeLoom.host(Pattern("host"))
        val joinerSeam = compositeLoom.join(InMemoryTag("joiner"))
        val hostRoom = SeamRoom(
            seam = hostSeam,
            role = SessionRole.Host,
            memberName = "host",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }
        SeamRoom(
            seam = joinerSeam,
            role = SessionRole.Joiner,
            memberName = "joiner",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }
        hostRoom.roster.first { it.isNotEmpty() }

        val leftEvents = mutableListOf<MembershipEvent.Left>()
        val collectJob = backgroundScope.launch {
            hostRoom.events.filterIsInstance<MembershipEvent.Left>().collect { leftEvents.add(it) }
        }

        // Both host-side plies tear → the composite rolls up to Weaving (#1367), not a derived Torn.
        plyALoom.links[0].tear()
        plyBLoom.links[0].tear()

        // Advance well under the heartbeat timeout (200ms) so the silence-based eviction path cannot
        // fire — this only flushes any immediate room reaction to the transport degrade.
        advanceTimeBy(50L)
        collectJob.cancel()

        assertIs<SeamState.Weaving>(
            hostSeam.state.value,
            "the composite must report Weaving (not a derived terminal Torn) when all plies tear (#1367)",
        )
        assertTrue(
            leftEvents.isEmpty(),
            "host must NOT evict on a transient all-plies-torn — composite reports Weaving (#1367): $leftEvents",
        )
        assertTrue(hostRoom.roster.value.isNotEmpty(), "the roster must survive a transient all-plies-torn degrade")

        plyScope.cancel()
    }
}
