package us.tractat.kuilt.session.partition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Direction
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.FaultySeam
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for [HeartbeatPartitionDetector].
 *
 * **Virtual time**: all timing goes through [kotlinx.coroutines.delay] so [runTest] /
 * [advanceTimeBy] controls every deadline. No wall-clock dependency.
 *
 * **Determinism**: [FaultySeam] + [FaultProfile] provide reproducible fault injection;
 * the clock lambda always returns a fixed or manually advanced [Instant].
 *
 * **Scope discipline**: [FaultySeam] and [HeartbeatPartitionDetector] are both started
 * on [backgroundScope] so their coroutines are cancelled automatically when [runTest] exits,
 * avoiding [kotlinx.coroutines.test.UncompletedCoroutinesError].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatPartitionDetectorTest {
    private val config =
        HeartbeatConfig(
            interval = 5.seconds,
            timeout = 15.seconds,
            reconnectWindow = 60.seconds,
        )

    // ── Harness helpers ───────────────────────────────────────────────────────

    /**
     * Builds a two-peer in-memory mesh: host and joiner.
     * Returns the [FaultySeam] wrapping the host's link (so tests can inject faults
     * on what the host receives) and the joiner's raw link.
     *
     * [scope] must be [backgroundScope] from the enclosing [runTest] so that the
     * [FaultySeam]'s internal collection coroutine outlives any [advanceTimeBy] call.
     */
    private suspend fun buildMesh(scope: CoroutineScope): Mesh {
        val factory = InMemoryLoom()
        val hostLink = factory.host(Pattern("host"))
        val joinerLink = factory.join(InMemoryTag("joiner"))
        val faultyHostLink = FaultySeam(hostLink, scope)
        return Mesh(hostLink.selfId, joinerLink.selfId, faultyHostLink, joinerLink)
    }

    private data class Mesh(
        val hostId: PeerId,
        val joinerId: PeerId,
        val hostLink: FaultySeam,
        val joinerLink: Seam,
    )

    private fun fixedClock(epochMs: Long): () -> Instant = { Instant.fromEpochMilliseconds(epochMs) }

    // ── Steady state ──────────────────────────────────────────────────────────

    @Test
    fun `healthy peer no events emitted during normal traffic`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            val clock = fixedClock(0)
            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            val events = mutableListOf<PartitionEvent>()
            val collectJob = backgroundScope.async { detector.events.toList(events) }

            detector.start(backgroundScope)

            // Advance 60 s — three full timeout windows — with no faults.
            advanceTimeBy(60_000)

            detector.stop()
            collectJob.cancel()

            assertEquals(emptyList(), events)
        }

    // ── Timeout path ──────────────────────────────────────────────────────────

    @Test
    fun `peer stops responding PeerUnresponsive Timeout emitted after timeout`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }

            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            // Collect exactly one event.
            val eventDeferred = async { detector.events.take(1).toList() }

            detector.start(backgroundScope)
            // Drop all frames from the joiner side so pongs never arrive.
            mesh.hostLink.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Advance past the timeout — detector pings every 5 s and checks silence.
            nowMs = config.timeout.inWholeMilliseconds + config.interval.inWholeMilliseconds
            advanceTimeBy(nowMs)

            val events = eventDeferred.await()
            val event = assertIs<PartitionEvent.PeerUnresponsive>(events.single())
            assertEquals(PartitionEvent.Reason.Timeout, event.reason)
            assertEquals(mesh.joinerId, event.peerId)

            detector.stop()
        }

    // ── Recovery path ─────────────────────────────────────────────────────────

    @Test
    fun `peer recovers before reconnect window PeerRecovered emitted`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }

            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            // Collect the first two events: Unresponsive then Recovered.
            val eventsDeferred = async { detector.events.take(2).toList() }

            detector.start(backgroundScope)

            // Partition: drop all inbound (joiner pongs blocked).
            mesh.hostLink.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Advance past timeout so Unresponsive fires.
            nowMs = config.timeout.inWholeMilliseconds + config.interval.inWholeMilliseconds
            advanceTimeBy(nowMs)

            // Heal the partition and simulate a frame from the joiner resetting the clock.
            mesh.hostLink.heal()
            nowMs += config.interval.inWholeMilliseconds
            detector.observedPeer(mesh.joinerId)

            // Advance one more poll interval so the detector re-evaluates silence.
            advanceTimeBy(config.interval.inWholeMilliseconds)

            val events = eventsDeferred.await()
            assertEquals(2, events.size)
            assertIs<PartitionEvent.PeerUnresponsive>(events[0])
            val recovered = assertIs<PartitionEvent.PeerRecovered>(events[1])
            assertEquals(mesh.joinerId, recovered.peerId)

            detector.stop()
        }

    // ── Loss path ─────────────────────────────────────────────────────────────

    @Test
    fun `peer absent for full reconnect window PeerLost emitted and detector stops`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }

            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            val eventsDeferred = async { detector.events.toList() }

            detector.start(backgroundScope)

            // Partition immediately.
            mesh.hostLink.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Advance the clock past timeout + full reconnect window.
            nowMs = config.timeout.inWholeMilliseconds +
                config.reconnectWindow.inWholeMilliseconds +
                config.interval.inWholeMilliseconds * 2
            advanceTimeBy(nowMs)

            val events = eventsDeferred.await()
            assertEquals(
                2,
                events.size,
                "expected [PeerUnresponsive, PeerLost] but got $events",
            )
            assertIs<PartitionEvent.PeerUnresponsive>(events[0])
            val lost = assertIs<PartitionEvent.PeerLost>(events[1])
            assertEquals(mesh.joinerId, lost.peerId)
        }

    // ── Backpressure path ─────────────────────────────────────────────────────

    @Test
    fun `onBackpressure emits PeerUnresponsive Backpressure`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            val clock = fixedClock(0)

            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            val eventDeferred = async { detector.events.take(1).toList() }

            detector.start(backgroundScope)

            // Signal backpressure directly (the leader does this when the outbound buffer
            // exceeds the ceiling — D-006).
            detector.onBackpressure(mesh.joinerId)

            // Advance one interval so the heartbeat loop evaluates the pending flag.
            advanceTimeBy(config.interval.inWholeMilliseconds)

            val events = eventDeferred.await()
            val event = assertIs<PartitionEvent.PeerUnresponsive>(events.single())
            assertEquals(PartitionEvent.Reason.Backpressure, event.reason)

            detector.stop()
        }

    @Test
    fun `FaultProfile BufferCeiling backpressure signal triggers PeerUnresponsive`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            val clock = fixedClock(0)

            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            val eventDeferred = async { detector.events.take(1).toList() }

            detector.start(backgroundScope)

            // Apply a ceiling of 0 outbound frames — every send is dropped immediately.
            mesh.hostLink.setFaultProfile(FaultProfile.BufferCeiling(maxOutbound = 0))

            // In production the leader observes the drop and calls onBackpressure.
            detector.onBackpressure(mesh.joinerId)

            advanceTimeBy(config.interval.inWholeMilliseconds)

            val events = eventDeferred.await()
            val event = assertIs<PartitionEvent.PeerUnresponsive>(events.single())
            assertEquals(PartitionEvent.Reason.Backpressure, event.reason)

            detector.stop()
        }

    // ── Transport close path ──────────────────────────────────────────────────

    @Test
    fun `transport closed PeerUnresponsive TransportClosed fires immediately`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            val clock = fixedClock(0)

            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            val eventDeferred = async { detector.events.take(1).toList() }

            detector.start(backgroundScope)

            // Close the links — this terminates the incoming flow.
            mesh.joinerLink.close()
            mesh.hostLink.close()

            advanceTimeBy(1_000)

            val events = eventDeferred.await()
            val event = assertIs<PartitionEvent.PeerUnresponsive>(events.single())
            assertEquals(PartitionEvent.Reason.TransportClosed, event.reason)

            detector.stop()
        }

    // ── Frame namespace isolation ─────────────────────────────────────────────

    @Test
    fun `PING_PREFIX and PONG_PREFIX are distinct reserved namespaces`() {
        assertEquals("kuilt.heartbeat.ping", HeartbeatPartitionDetector.PING_PREFIX)
        assertEquals("kuilt.heartbeat.pong", HeartbeatPartitionDetector.PONG_PREFIX)
        assertTrue(
            HeartbeatPartitionDetector.isPingFrame(Swatch(HeartbeatPartitionDetector.pingPayload())),
        )
        assertTrue(
            HeartbeatPartitionDetector.isPongFrame(Swatch(HeartbeatPartitionDetector.pongPayload())),
        )
    }

    @Test
    fun `onBackpressure with different peerId is ignored`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            val clock = fixedClock(0)
            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            val events = mutableListOf<PartitionEvent>()
            val collectJob = backgroundScope.async { detector.events.toList(events) }

            detector.start(backgroundScope)

            // Call onBackpressure with a different PeerId — should be a no-op.
            detector.onBackpressure(PeerId("some-other-peer"))

            advanceTimeBy(config.interval.inWholeMilliseconds)

            detector.stop()
            collectJob.cancel()

            assertEquals(emptyList(), events)
        }

    @Test
    fun `observedPeer with different peerId does not reset timeout`() =
        runTest {
            val mesh = buildMesh(backgroundScope)
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }

            val detector = HeartbeatPartitionDetector(mesh.hostLink, mesh.joinerId, config, clock)

            val eventDeferred = async { detector.events.take(1).toList() }

            detector.start(backgroundScope)
            mesh.hostLink.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Observe a different peer — should not prevent Timeout firing.
            nowMs = config.timeout.inWholeMilliseconds + config.interval.inWholeMilliseconds
            detector.observedPeer(PeerId("somebody-else"))
            advanceTimeBy(nowMs)

            val events = eventDeferred.await()
            val event = assertIs<PartitionEvent.PeerUnresponsive>(events.single())
            assertEquals(PartitionEvent.Reason.Timeout, event.reason)

            detector.stop()
        }
}
