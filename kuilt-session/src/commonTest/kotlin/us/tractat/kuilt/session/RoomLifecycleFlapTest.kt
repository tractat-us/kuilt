package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Direction
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.FaultySeam
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

/**
 * Resilience tests for [SeamRoom] backed by a [FlakyLifecycleLoom].
 *
 * Exercises two scenarios:
 * 1. A `Woven → Weaving → Woven` blip — the room survives and delivery resumes.
 * 2. Joiner sees [MembershipEvent.HostLost] after the host link goes permanently silent.
 *
 * All timing uses virtual time via [runTest] + [advanceTimeBy]. The clock is advanced
 * in lockstep with virtual time so [us.tractat.kuilt.session.partition.HeartbeatPartitionDetector]
 * silence-calculation sees elapsed time correctly.
 *
 * **Task 6 architectural finding:** [MembershipEvent.HostLost] does NOT fire from
 * `FlakyLifecycleSeam.tear()` alone. When `tear()` is called, `_peers` collapses to
 * empty immediately, causing [SeamRoom]'s peers watcher to fire `Left(Normal)` for the
 * host and cancel the heartbeat detector — before the detector can escalate to
 * [us.tractat.kuilt.session.partition.PartitionEvent.PeerLost]. As a result, `HostLost`
 * is never emitted from a `tear()`. The session layer keys off **heartbeat timeout**, not
 * `SeamState`. A follow-up issue should track adding direct `SeamState.Torn` observation
 * so `HostLost` fires faster and more directly than waiting for heartbeat expiry.
 *
 * The `HostLost` test therefore simulates "permanent link loss" via [FaultySeam.DropAll]
 * (which stops frames without changing the peer set) rather than `tear()`.
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
     * Verifies [MembershipEvent.HostLost] fires after permanent link silence.
     *
     * Uses [FaultySeam] with [FaultProfile.DropAll] (not `tear()`) to simulate a dead
     * host link while keeping the peer in the mesh — required for the heartbeat detector
     * to remain active long enough to escalate to [us.tractat.kuilt.session.partition.PartitionEvent.PeerLost].
     *
     * See class-level KDoc for the full architectural finding about `tear()`.
     */
    @Test
    fun `joiner sees HostLost when the host link goes permanently silent`() = runTest {
        val innerLoom = InMemoryLoom()

        // Wrap the host seam in FaultySeam so we can drop all frames without removing
        // the host from the mesh (unlike tear(), which removes the peer and fires Left(Normal)).
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
     * Documents that `tear()` on the host seam produces [MembershipEvent.Left] with
     * [LeaveReason.Normal] — NOT [MembershipEvent.HostLost].
     *
     * This is the confirming test for the class-level architectural finding: `tear()` collapses
     * `peers` immediately, causing the peers watcher to fire `Left(Normal)` and cancel the
     * heartbeat detector before it can escalate.
     */
    @Test
    fun `tear on the host seam produces Left Normal not HostLost`() = runTest {
        val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
        val factory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)
        val hostRoom = factory.host(Pattern("host"))
        val joinerRoom = factory.join(InMemoryTag("joiner"))
        hostRoom.roster.first { it.isNotEmpty() }

        val leftDeferred = async {
            joinerRoom.events.filterIsInstance<MembershipEvent.Left>().first()
        }

        // Tear the host seam — peers collapses to empty, peers watcher fires Left(Normal).
        loom.links[0].tear()

        val leftEvent = leftDeferred.await()
        assertIs<LeaveReason.Normal>(leftEvent.reason)
    }
}
