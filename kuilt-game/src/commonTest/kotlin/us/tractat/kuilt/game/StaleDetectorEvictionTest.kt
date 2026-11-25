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
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A host evicts a voter on a detector's loss only while that detector is the one watching him now
 * (#3672), and on his own vacate only while he is still vacating (#3684).
 *
 * Xavier's link drops for longer than the reconnect window, so the host evicts him. His link comes
 * back while the host is still filling seats ([ReturnPolicy.Quorum]), so he is admitted again, and a
 * new detector watches him once Yasmin joins. A loss his old detector reported then reaches the
 * eviction loop. Acted on, it takes the seat of a voter whose link is healthy, and the host never
 * seats him again.
 *
 * On one thread that loss never arrives late: each detector reports one loss, and the eviction that
 * acts on it cancels the detector. It arrives late when a detector's collector takes the loss and
 * loses its thread before sending it, while a vacate cancels that detector and the voter is evicted
 * and readmitted. [VoterLivenessMonitor.requestEvictionForTest] hands over the request that late
 * loss carries, the voter and the old detector's job, so the test reaches it on every target, in
 * virtual time. The control is the same loss from the current detector.
 *
 * **The rig stands on #3139's re-admission bugs.** Xavier gets a second detector only because the
 * latecomer loop re-seats a voter the eviction loop has just evicted, and because the eviction loop
 * then gives its replacement's detector to the first new voter it sees, Xavier, rather than to
 * Yasmin. When #3139 is fixed, neither happens, and [rejoinedOnce] has to be rebuilt through a seam
 * that registers a second detector for the same voter.
 *
 * Every wait is bounded in virtual time ([WAIT_BOUND]), so a wedge fails as the step that stopped.
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

            r.monitor.requestEvictionForTest(EvictionRequest.Lost(r.voter, r.old))
            val afterStale = settledMembership(r.host.node)
            val staleWatched = r.monitor.detectorJobForTest(r.voter) === r.current && !r.current.isCancelled
            watcher.cancel()

            val left = async { r.host.node.membership.first { r.voter !in it.voters } }
            runCurrent()
            r.monitor.requestEvictionForTest(EvictionRequest.Lost(r.voter, r.current))
            val controlLeft = withTimeoutOrNull(WAIT_BOUND) { left.await() } != null

            assertAll(
                { assertFalse(leftDuringStale, "a stale loss never takes the voter's seat") },
                { assertTrue(r.voter in afterStale.voters, "the voter is still seated once it settles: $afterStale") },
                { assertTrue(staleWatched, "and the detector watching it now is left running") },
                { assertTrue(controlLeft, "control: the same loss from the current detector evicts the voter") },
                { assertTrue(r.current.isCancelled, "control: and stops that detector") },
            )
        }

    /**
     * A vacate is the voter's own word, so it frees a watched voter's seat whatever detector is
     * watching him. Asked for directly: the room-level vacate path is rarely reached, since the
     * watcher re-reads the vacaters only when the presence key set changes.
     *
     * Xavier really leaves first, and the host's board shows it, because a vacate counts only while
     * he is still vacating (#3684). Leaving stops him answering pings, so his detector would lose him
     * one loss window later. The seat is freed well inside that window, so it was the vacate.
     */
    @Test
    fun `a vacate from a watched voter frees its seat`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val t = threeSeated()
            val watching = assertNotNull(t.monitor.detectorJobForTest(t.xavier), "rig: a detector watches Xavier")
            val left = async { t.host.node.membership.first { t.xavier !in it.voters } }
            runCurrent()
            assertFalse(left.isCompleted, "rig: Xavier is seated before he vacates")

            val leftAt = testScheduler.currentTime
            t.xSession.leave()
            awaitVacating(t, vacating = true)

            t.monitor.requestEvictionForTest(EvictionRequest.Vacated(t.xavier))
            val freed = withTimeoutOrNull(t.liveness.reconnectWindow) { left.await() } != null
            val freedAfterMs = testScheduler.currentTime - leftAt

            assertAll(
                { assertTrue(freed, "a vacate frees the seat inside the reconnect window") },
                {
                    assertTrue(
                        freedAfterMs < (t.liveness.timeout + t.liveness.reconnectWindow).inWholeMilliseconds,
                        "and before a loss could have freed it: ${freedAfterMs}ms after he left",
                    )
                },
                { assertTrue(watching.isCancelled, "and stops the detector that watched him") },
                { assertNull(t.monitor.detectorJobForTest(t.xavier), "and unregisters it") },
            )
        }

    /**
     * A vacate queued while the eviction loop is busy counts only if the voter is still vacating
     * when the loop acts on it (#3684).
     *
     * The loop evicts Ada and waits for someone to take her seat. Meanwhile Xavier says he is
     * leaving, and his vacate is queued behind that wait. Then he says he is present again. When
     * Yasmin takes Ada's seat, the loop reaches Xavier's vacate, which no longer holds: he keeps his
     * seat, and a detector watches him again.
     *
     * The watcher stops a vacater's detector when it sees the vacate, so the test stops it too.
     * Xavier says he is present again on the same presence instance, which keeps his leave latch, so
     * he answers no pings, and the detector the loop starts for him loses him one loss window later.
     * The test reads the seat at the moment the loop acts, which is inside that window.
     */
    @Test
    fun `a vacate the voter took back before the loop reached it keeps him seated`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val t = threeSeated()
            val ada = NodeId(t.aRaw.selfId.value)

            // Ada's loss sends the loop into its wait for a replacement.
            val adaDetector = assertNotNull(t.monitor.detectorJobForTest(ada), "rig: a detector watches Ada")
            t.monitor.requestEvictionForTest(EvictionRequest.Lost(ada, adaDetector))
            within("Ada is evicted") { t.host.node.membership.first { ada !in it.voters } }

            // Xavier leaves, and the watcher's two steps follow: his detector stops, his vacate is queued.
            val xDetector = assertNotNull(t.monitor.detectorJobForTest(t.xavier), "rig: a detector watches Xavier")
            t.xSession.leave()
            awaitVacating(t, vacating = true)
            xDetector.cancel()
            val vacatingWhenQueued = t.xavier.value in t.hostPresence.vacaters().map { it.value }
            t.monitor.requestEvictionForTest(EvictionRequest.Vacated(t.xavier))

            // He takes it back before the loop reaches it.
            assertNotNull(t.xSession.lobbyPresence, "rig: Xavier has a lobby presence").declarePresent()
            awaitVacating(t, vacating = false)
            val vacatingWhenActedOn = t.xavier.value in t.hostPresence.vacaters().map { it.value }

            var leftSeat = false
            val seatWatcher = backgroundScope.launch {
                t.host.node.membership.collect { if (t.xavier !in it.voters) leftSeat = true }
            }
            runCurrent()

            // Yasmin takes Ada's seat, so the loop moves on to Xavier's vacate.
            val yRaw = t.loom.join(InMemoryTag("yasmin"))
            val yasmin = NodeId(yRaw.selfId.value)
            backgroundScope.launch { backgroundScope.gameJoin(yRaw, raftConfig = fastRaftConfig(seed = 4L)) }
            within("Yasmin takes Ada's seat") { t.host.node.membership.first { yasmin in it.voters } }
            var rewatched: Job? = null
            repeat(REGISTRATION_STEPS) {
                if (rewatched == null && !leftSeat) {
                    rewatched = t.monitor.detectorJobForTest(t.xavier)?.takeIf { it !== xDetector }
                    if (rewatched == null) advanceTimeBy(1L)
                }
            }
            val seatedWhenActedOn = !leftSeat && t.xavier in t.host.node.membership.value.voters
            seatWatcher.cancel()

            assertAll(
                { assertTrue(vacatingWhenQueued, "rig: Xavier was vacating when his vacate was queued") },
                { assertFalse(vacatingWhenActedOn, "rig: and was not once he said he was present") },
                { assertTrue(seatedWhenActedOn, "a vacate he took back keeps him seated") },
                { assertNotNull(rewatched, "and the loop starts a detector for him again") },
                { assertTrue(rewatched?.isActive == true, "which is running") },
            )
        }

    private class ThreeSeated(
        val loom: InMemoryLoom,
        val aRaw: Seam,
        val host: GameSession,
        val hostPresence: GamePresence,
        val monitor: VoterLivenessMonitor,
        val xSession: GameSession,
        val xavier: NodeId,
        val liveness: HeartbeatConfig,
    )

    /** A host of three seats, full membership, with Ada and Xavier seated and watched. */
    private suspend fun TestScope.threeSeated(): ThreeSeated {
        val loom = InMemoryLoom()
        val (hostRaw, aRaw, xRaw) = seats(loom, 3)
        val liveness = fastLivenessConfig()
        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 3,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = liveness,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            )
        }
        val aDeferred = async { backgroundScope.gameJoin(aRaw, raftConfig = fastRaftConfig(seed = 2L)) }
        val xDeferred = async { backgroundScope.gameJoin(xRaw, raftConfig = fastRaftConfig(seed = 3L)) }
        val host = within("the host seats everyone") { hostDeferred.await() }
        within("Ada is admitted") { aDeferred.await() }
        val xSession = within("Xavier is admitted") { xDeferred.await() }
        return ThreeSeated(
            loom = loom,
            aRaw = aRaw,
            host = host,
            hostPresence = assertNotNull(host.lobbyPresence, "rig: the host has a lobby presence"),
            monitor = assertNotNull(host.livenessMonitor, "rig: the host monitors its voters"),
            xSession = xSession,
            xavier = NodeId(xRaw.selfId.value),
            liveness = liveness,
        )
    }

    /** Steps virtual time until the host's board shows Xavier [vacating], or not, failing past the bound. */
    private fun TestScope.awaitVacating(t: ThreeSeated, vacating: Boolean) {
        repeat(REGISTRATION_STEPS) {
            if ((t.xavier.value in t.hostPresence.vacaters().map { it.value }) == vacating) return
            advanceTimeBy(1L)
            runCurrent()
        }
        fail("rig: the host's board shows Xavier ${if (vacating) "vacating" else "present"}")
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
     * detector watches him. See the class KDoc for the bugs this path rests on.
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
        val host = within("the host seats a quorum") { hostDeferred.await() }
        within("Ada is admitted") { aDeferred.await() }
        within("Xavier is admitted") { xDeferred.await() }

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
        within("Xavier is evicted once lost") { evicted.await() }
        x.heal()

        // The latecomer loop readmits Xavier at once, with no detector, and the eviction loop is still
        // waiting for a replacement of its own. When Yasmin joins it wakes and gives its new detector
        // to the first voter it did not have before, which is Xavier (#3139).
        within("the latecomer loop readmits Xavier") { host.node.membership.first { voter in it.voters } }
        val yRaw = loom.join(InMemoryTag("yasmin"))
        backgroundScope.launch { backgroundScope.gameJoin(yRaw, raftConfig = fastRaftConfig(seed = 4L)) }
        within("Yasmin takes the last seat") { host.node.membership.first { it.voters.size == SEATS } }
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

    /** Waits for [block] at most [WAIT_BOUND] of virtual time, failing as [what] if it does not finish. */
    private suspend fun <T : Any> within(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(WAIT_BOUND) { block() } ?: fail("rig: $what, within $WAIT_BOUND of virtual time")

    private companion object {
        const val SEATS = 4
        const val REGISTRATION_STEPS = 50

        /** A virtual-time bound: `withTimeoutOrNull` under `runTest` measures the test scheduler. */
        val WAIT_BOUND: Duration = 5.seconds
    }
}
