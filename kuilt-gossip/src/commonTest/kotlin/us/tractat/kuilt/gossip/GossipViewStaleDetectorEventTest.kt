package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A view acts on what a neighbour's detector reports only while that detector is still the
 * neighbour's (#3672). B leaves the hub's roster and comes back, so the hub stops B's detector and
 * starts another; an edge-down the old detector reported then reaches the hub.
 *
 * Acted on, it drops a healthy B from the active view, and it does more than reshuffle: B goes into
 * the failed set, which keeps it out of re-selection for as long as it stays in the roster. The new
 * detector never saw B go quiet, so nothing ever lets it back.
 *
 * Two ways there. A collector on a multi-threaded dispatcher can take the event, lose its thread,
 * and send it after its detector was replaced: the send to an unlimited channel does not suspend, so
 * cancelling the collector does not stop it. And an edge-down already queued behind the roster
 * change that stops its detector is processed after that stop. [GossipView.handleDetectorEventForTest]
 * hands over the event with the old detector, which reaches both on every target, in virtual time.
 *
 * Each test ends with a control: the same event from the current detector drops B. So a stale event
 * that does nothing is the check refusing it, not a seam that reaches nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipViewStaleDetectorEventTest {
    // The whole test runs well inside one timeout, so no detector times out on its own.
    private val config = HeartbeatConfig(interval = 1.seconds, timeout = 2.seconds, reconnectWindow = 2.seconds)

    private val hub = PeerId("hub")
    private val a = PeerId("a")
    private val b = PeerId("b")
    private val c = PeerId("c")

    @Test
    fun aStaleUnresponsiveLeavesTheReturnedNeighbourActive() =
        staleEdgeDownKeepsBActive { at -> PartitionEvent.PeerUnresponsive(b, at, PartitionEvent.Reason.Timeout) }

    @Test
    fun aStaleLossLeavesTheReturnedNeighbourActive() = staleEdgeDownKeepsBActive { at -> PartitionEvent.PeerLost(b, at) }

    private fun staleEdgeDownKeepsBActive(event: (Instant) -> PartitionEvent) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val roster = MutableStateFlow(setOf(hub, a, b, c))
            val view =
                GossipView(
                    selfId = hub,
                    seam = FakeSeam(selfId = hub, initialPeers = roster.value),
                    roster = roster,
                    rawIncoming = MutableSharedFlow<Swatch>().asSharedFlow(),
                    random = Random(1),
                    clock = { now() },
                    config = config,
                    topology = FullFanout,
                )
            view.start(backgroundScope)
            settle()
            val old = assertNotNull(view.detectorForTest(b), "rig: the hub watches B")

            roster.value = roster.value - b
            settle()
            val whileAway = view.active.value
            val detectorWhileAway = view.detectorForTest(b)

            roster.value = roster.value + b
            settle()
            val current = assertNotNull(view.detectorForTest(b), "rig: the hub watches the returned B")
            val beforeStale = view.active.value

            view.handleDetectorEventForTest(event(now()), old)
            runCurrent()
            val afterStale = view.active.value
            val detectorAfterStale = view.detectorForTest(b)

            view.handleDetectorEventForTest(event(now()), current)
            runCurrent()
            val afterControl = view.active.value

            assertAll(
                { assertFalse(b in whileAway, "rig: B left the active view when it left the roster") },
                { assertNull(detectorWhileAway, "rig: and its old detector was stopped") },
                { assertNotSame(old, current, "rig: the returned B is watched by a new detector") },
                { assertEquals(setOf(a, b, c), beforeStale, "rig: B is active again") },
                { assertEquals(setOf(a, b, c), afterStale, "a stale edge-down leaves the healthy B active") },
                { assertSame(current, detectorAfterStale, "a stale edge-down leaves B's current detector running") },
                { assertEquals(setOf(a, c), afterControl, "control: from the current detector, B is dropped") },
            )
        }

    /** Advances past the widest recompute jitter so a roster change lands. */
    private fun TestScope.settle() {
        advanceTimeBy(GossipView.DEFAULT_JITTER.endInclusive.inWholeMilliseconds + 1)
        runCurrent()
    }

    private fun TestScope.now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
}
