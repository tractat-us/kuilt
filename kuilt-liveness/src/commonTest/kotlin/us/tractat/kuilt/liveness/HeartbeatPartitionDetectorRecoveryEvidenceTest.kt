@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.liveness

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `PeerRecovered` requires **evidence of liveness**, never merely elapsed time (#1966).
 *
 * On real hardware the host's detector emitted `PeerRecovered` for a peer whose transport was
 * already gone, purely because not enough wall-clock silence had accrued yet. The outer loop then
 * re-observed the peer's absence and re-fired `PeerUnresponsive`, so presence flapped
 * `Partitioned → Recovered → Partitioned` once per `interval` and each cycle re-armed the
 * consumer's reconnect window.
 *
 * The property pinned here is the one [PartitionEvent.PeerRecovered]'s own KDoc already states —
 * "a previously unresponsive peer has **resumed sending frames**" — so recovery must be gated on
 * `lastSeen` having *advanced* since the `PeerUnresponsive`, not on the silence happening to sit
 * under [HeartbeatConfig.timeout].
 *
 * **Virtual time only.** The clock is wired to the test scheduler, so silence accrues exactly with
 * `advanceTimeBy` and no wall-clock value is load-bearing.
 */
class HeartbeatPartitionDetectorRecoveryEvidenceTest {

    private val config =
        HeartbeatConfig(
            interval = 5.seconds,
            timeout = 15.seconds,
            reconnectWindow = 60.seconds,
        )

    private val self = PeerId("self")
    private val target = PeerId("target")

    /**
     * A [Seam] whose roster and inbound stream are both driven by the test: [dropTarget] /
     * [restoreTarget] move the peer in and out of [peers], and [deliverFromTarget] pushes a real
     * frame through [incoming] so the detector's own collector calls `observedPeer`.
     */
    private class ControllableLink(
        override val selfId: PeerId,
        private val target: PeerId,
    ) : Seam {
        private val mutablePeers = MutableStateFlow(setOf(selfId, target))
        override val peers: StateFlow<Set<PeerId>> = mutablePeers.asStateFlow()
        private val mutableState = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> = mutableState.asStateFlow()
        private val mutableIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 64)
        override val incoming: Flow<Swatch> = mutableIncoming.asSharedFlow()

        override suspend fun broadcast(payload: ByteArray) = Unit

        override suspend fun sendTo(
            peer: PeerId,
            payload: ByteArray,
        ) = Unit

        override suspend fun close(reason: CloseReason) = Unit

        fun dropTarget() = mutablePeers.update { it - target }

        fun restoreTarget() = mutablePeers.update { it + target }

        suspend fun deliverFromTarget(payload: ByteArray) = mutableIncoming.emit(Swatch(payload))
    }

    /** Reads virtual time, so `silenceMs` tracks `advanceTimeBy` exactly. */
    private fun TestScope.virtualClock(): () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

    private fun List<PartitionEvent>.countOf(recovered: Boolean): Int =
        count { if (recovered) it is PartitionEvent.PeerRecovered else it is PartitionEvent.PeerUnresponsive }

    // ── The reported bug ──────────────────────────────────────────────────────

    /**
     * #1966's hardware shape: the transport tears with `lastSeen` still fresher than
     * [HeartbeatConfig.timeout], and the peer never comes back. Exactly one `PeerUnresponsive`
     * and one `PeerLost` may be emitted, and no `PeerRecovered` at all — nothing recovered.
     */
    @Test
    fun transportClosedPeerThatNeverReturnsEmitsNoPhantomRecovered() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val link = ControllableLink(self, target)
            val detector = HeartbeatPartitionDetector(link, target, config, virtualClock())
            val events = mutableListOf<PartitionEvent>()
            backgroundScope.launch { detector.events.toList(events) }

            detector.start(backgroundScope)
            // One interval so the loop latches the peer as present; `lastSeen` is still well
            // inside `timeout` when the transport goes — the hardware shape from #1966.
            advanceTimeBy(config.interval.inWholeMilliseconds)
            link.dropTarget()

            // Run the whole reconnect window out, plus slack for the final poll.
            advanceTimeBy(
                config.reconnectWindow.inWholeMilliseconds + config.interval.inWholeMilliseconds * 3,
            )

            assertAll(
                { assertEquals(0, events.countOf(recovered = true), "no peer recovered, but got $events") },
                { assertEquals(1, events.countOf(recovered = false), "one unresponsive edge, but got $events") },
                { assertEquals(1, events.count { it is PartitionEvent.PeerLost }, "expected PeerLost in $events") },
            )
        }

    /**
     * The same absence signalled the other way: the peer is still in the roster, so a
     * roster-membership gate would pass, but no frame has arrived since the `PeerUnresponsive`.
     * Pins that *roster presence is not liveness evidence*.
     */
    @Test
    fun backpressureWithThePeerStillInTheRosterEmitsNoPhantomRecovered() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val link = ControllableLink(self, target) // target never leaves `peers`
            val detector = HeartbeatPartitionDetector(link, target, config, virtualClock())
            val events = mutableListOf<PartitionEvent>()
            backgroundScope.launch { detector.events.toList(events) }

            detector.start(backgroundScope)
            advanceTimeBy(config.interval.inWholeMilliseconds)
            detector.onBackpressure(target)

            // One tick to consume the pending flag, one poll of the recovery window. Silence is
            // still under `timeout` at that poll, which is exactly what used to fake a recovery.
            advanceTimeBy(config.interval.inWholeMilliseconds + 1)

            val unresponsive = assertIs<PartitionEvent.PeerUnresponsive>(events.firstOrNull(), "got $events")
            assertAll(
                { assertEquals(PartitionEvent.Reason.Backpressure, unresponsive.reason) },
                { assertEquals(0, events.countOf(recovered = true), "no peer recovered, but got $events") },
                { assertEquals(1, events.countOf(recovered = false), "one unresponsive edge, but got $events") },
            )
        }

    /**
     * A peer that rejoins the roster after a transport close but sends nothing is still not
     * recovered — and one real inbound frame is what recovers it.
     */
    @Test
    fun rejoiningTheRosterDoesNotRecoverUntilAFrameArrives() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val link = ControllableLink(self, target)
            val detector = HeartbeatPartitionDetector(link, target, config, virtualClock())
            val events = mutableListOf<PartitionEvent>()
            backgroundScope.launch { detector.events.toList(events) }

            detector.start(backgroundScope)
            advanceTimeBy(config.interval.inWholeMilliseconds)
            link.dropTarget()

            // Let the transport-closed edge fire, then put the peer straight back in the roster
            // while the measured silence is still under `timeout`.
            advanceTimeBy(1_000)
            link.restoreTarget()

            advanceTimeBy(config.interval.inWholeMilliseconds)
            assertAll(
                { assertEquals(0, events.countOf(recovered = true), "roster presence is not evidence: $events") },
                { assertEquals(1, events.countOf(recovered = false), "one unresponsive edge, but got $events") },
            )

            // Now the peer actually sends something.
            link.deliverFromTarget(byteArrayOf(7))
            advanceTimeBy(config.interval.inWholeMilliseconds)

            assertAll(
                { assertEquals(1, events.countOf(recovered = true), "the frame recovers the peer: $events") },
                { assertEquals(0, events.count { it is PartitionEvent.PeerLost }, "no loss expected in $events") },
            )
        }

    // ── The lane that must keep working ───────────────────────────────────────

    /**
     * Regression guard for the legitimate `Reason.Timeout` recovery lane: silence crosses the
     * timeout, then a real inbound frame arrives and `PeerRecovered` still fires.
     */
    @Test
    fun timeoutRecoveryStillFiresWhenAFrameArrives() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val link = ControllableLink(self, target) // target never leaves `peers`
            val detector = HeartbeatPartitionDetector(link, target, config, virtualClock())
            val eventsDeferred = async { detector.events.take(2).toList() }

            detector.start(backgroundScope)
            // No inbound traffic at all: the silence crosses `timeout`.
            advanceTimeBy(config.timeout.inWholeMilliseconds + config.interval.inWholeMilliseconds)

            // The peer resumes. One real frame through `incoming` advances `lastSeen`.
            link.deliverFromTarget(byteArrayOf(7))
            advanceTimeBy(config.interval.inWholeMilliseconds * 2)

            val events = eventsDeferred.await()
            val unresponsive = assertIs<PartitionEvent.PeerUnresponsive>(events[0])
            assertAll(
                { assertEquals(PartitionEvent.Reason.Timeout, unresponsive.reason) },
                { assertIs<PartitionEvent.PeerRecovered>(events[1], "expected a recovery in $events") },
            )
        }
}
