@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A host evicts a voter only on the word of the detector that is watching it now (#3672).
 *
 * Xavier's link drops for longer than the reconnect window, so the host evicts him. His link comes
 * back while the host is still filling seats ([ReturnPolicy.Quorum]), so he is admitted again, and a
 * new detector watches him once Yasmin joins. A loss his old detector reported then reaches the
 * eviction loop, which knows only his name. Acted on, it takes the seat of a voter whose link is
 * healthy, and the host never seats him again.
 *
 * On one thread that loss never arrives late: each detector reports one loss, and the eviction that
 * acts on it cancels the detector. It arrives late when a second request for the same voter was
 * already queued (a vacate beside the loss), or when a detector's collector takes the loss and loses
 * its thread before sending it, while a vacate cancels that detector and the voter is evicted and
 * readmitted. [VoterLivenessMonitor.requestEvictionForTest] hands over the pair that late request
 * carries, the voter and the old detector's job, so the test reaches it on every target, in virtual
 * time. The control is the same request from the current detector.
 */
class StaleDetectorEvictionTest {

    @Test
    fun `a loss from the detector a rejoin replaced keeps the voter seated`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val r = rejoinedOnce()
            var leftDuringStale = false
            val watcher = backgroundScope.launch {
                r.host.node.membership.collect { if (r.voter !in it.voters) leftDuringStale = true }
            }
            runCurrent()

            r.monitor.requestEvictionForTest(r.voter, r.old)
            val afterStale = settledMembership(r.host.node)
            val staleWatched = r.monitor.detectorJobForTest(r.voter) === r.current && !r.current.isCancelled
            watcher.cancel()

            val left = async { r.host.node.membership.first { r.voter !in it.voters } }
            runCurrent()
            r.monitor.requestEvictionForTest(r.voter, r.current)
            left.await()

            assertAll(
                { assertFalse(leftDuringStale, "a stale loss never takes the voter's seat") },
                { assertTrue(r.voter in afterStale.voters, "the voter is still seated once it settles: $afterStale") },
                { assertTrue(staleWatched, "and the detector watching it now is left running") },
                { assertTrue(r.current.isCancelled, "control: the same loss from the current detector evicts the voter") },
            )
        }

    private class Rejoined(
        val host: GameSession,
        val monitor: VoterLivenessMonitor,
        val voter: NodeId,
        val old: Job,
        val current: Job,
    )

    /**
     * A quorum-mode host of four seats with three admitted. Xavier is lost and evicted, the latecomer
     * loop readmits him while it still fills seats, and once Yasmin takes the fourth seat a new
     * detector watches him.
     */
    private suspend fun TestScope.rejoinedOnce(): Rejoined {
        val loom = InMemoryLoom()
        val (hostRaw, aRaw, xRaw) = seats(loom, 3)
        val liveness = fastLivenessConfig()
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = SEATS,
                returnAt = ReturnPolicy.Quorum,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = liveness,
                clock = clock,
            )
        }
        val aDeferred = async { backgroundScope.gameJoin(aRaw, raftConfig = fastRaftConfig(seed = 2L)) }
        val x = FaultySeam(xRaw, backgroundScope)
        val xDeferred = async { backgroundScope.gameJoin(x, raftConfig = fastRaftConfig(seed = 3L)) }
        val host = hostDeferred.await()
        aDeferred.await()
        xDeferred.await()

        val voter = NodeId(xRaw.selfId.value)
        val monitor = assertNotNull(host.livenessMonitor, "rig: the host monitors its voters")
        assertTrue(voter in host.node.membership.value.voters, "rig: Xavier is seated")
        val old = assertNotNull(monitor.detectorJobForTest(voter), "rig: a detector watches Xavier")

        val evicted = async { host.node.membership.first { voter !in it.voters } }
        runCurrent()
        x.partition(Direction.Outbound)
        val lossMs = (liveness.timeout + liveness.reconnectWindow + liveness.interval * 4).inWholeMilliseconds
        advanceTimeBy(lossMs)
        runCurrent()
        evicted.await()
        x.heal()

        // The latecomer loop readmits Xavier at once, with no detector, and the eviction loop is still
        // waiting for a replacement of its own. When Yasmin joins it wakes and gives its new detector
        // to the first voter it did not have before, which is Xavier.
        host.node.membership.first { voter in it.voters }
        val yRaw = loom.join(InMemoryTag("yasmin"))
        backgroundScope.launch { backgroundScope.gameJoin(yRaw, raftConfig = fastRaftConfig(seed = 4L)) }
        host.node.membership.first { it.voters.size == SEATS }
        var current: Job? = null
        repeat(REGISTRATION_STEPS) {
            if (current == null) {
                current = monitor.detectorJobForTest(voter)?.takeIf { it !== old }
                if (current == null) advanceTimeBy(1L)
            }
        }
        val replaced = assertNotNull(current, "rig: the readmitted voter has a new detector")
        assertNotSame(old, replaced, "rig: a new one")
        assertTrue(old.isCancelled, "rig: the old one was cancelled")
        // Long enough for a silent link to be lost again; it is healed, so the seat holds.
        advanceTimeBy(lossMs)
        val healthy = settledMembership(host.node)
        assertTrue(voter in healthy.voters, "rig: the readmitted voter holds its seat on its own: $healthy")
        assertFalse(replaced.isCancelled, "rig: its detector still watches it")
        return Rejoined(host, monitor, voter, old, replaced)
    }

    private companion object {
        const val SEATS = 4
        const val REGISTRATION_STEPS = 50
    }
}
