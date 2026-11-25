@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * After a host replaces a lost voter, it closes admission only if every seat is taken (#3683).
 *
 * A host that returns at a quorum ([ReturnPolicy.Quorum]) keeps a seat open for a latecomer. When
 * Xavier's device dies, the host evicts him and seats Yasmin in his place. One seat is still open, so
 * when Zara arrives she must be seated too. Before #3683 the eviction closed admission behind
 * Yasmin, with the roster still short, and Zara was turned away with [RosterFullException].
 *
 * The two controls hold the other half: when the roster was full before Xavier died, the replacement
 * fills it again, and a latecomer is turned away as before. One control returns at full membership,
 * the other at a quorum whose latecomer loop had already filled the roster.
 *
 * Every wait is bounded in virtual time ([WAIT_BOUND]), so a wedge fails as the step that stopped.
 */
class EvictionAdmissionTest {

    @Test
    fun `a latecomer is seated after a replacement while a seat is still open`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val r = replacedOnce(peerCount = 4, returnAt = ReturnPolicy.Quorum, seatedBeforeLoss = 3)
            assertEquals(3, r.afterReplacement.voters.size, "rig: one seat of four is still open: ${r.afterReplacement}")

            val zRaw = r.loom.join(InMemoryTag("zara"))
            val zara = NodeId(zRaw.selfId.value)
            within("Zara is seated") { backgroundScope.gameJoin(zRaw, raftConfig = fastRaftConfig(seed = 8L)) }
            val after = settledMembership(r.host.node)

            assertAll(
                { assertTrue(zara in after.voters, "the latecomer holds the open seat: $after") },
                { assertEquals(4, after.voters.size, "and the roster is full: $after") },
            )
        }

    @Test
    fun `under full membership a latecomer after a replacement is turned away`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val r = replacedOnce(peerCount = 3, returnAt = ReturnPolicy.FullMembership, seatedBeforeLoss = 3)
            assertLatecomerTurnedAway(r)
        }

    @Test
    fun `under quorum a latecomer after a replacement into a full roster is turned away`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val r = replacedOnce(peerCount = 3, returnAt = ReturnPolicy.Quorum, seatedBeforeLoss = 3)
            assertLatecomerTurnedAway(r)
        }

    private suspend fun TestScope.assertLatecomerTurnedAway(r: Replaced) {
        assertEquals(3, r.afterReplacement.voters.size, "rig: the replacement fills the roster: ${r.afterReplacement}")
        val zRaw = r.loom.join(InMemoryTag("zara"))
        // The bound sits inside, so a join left waiting fails as a named step, not a swallowed timeout.
        assertFailsWith<RosterFullException>("a full roster turns the latecomer away") {
            within("Zara is answered") { backgroundScope.gameJoin(zRaw, raftConfig = fastRaftConfig(seed = 8L)) }
        }
        val after = settledMembership(r.host.node)
        assertAll(
            { assertTrue(NodeId(zRaw.selfId.value) !in after.voters, "and seats nobody past it: $after") },
            { assertEquals(3, after.voters.size, "the roster holds its three: $after") },
        )
    }

    private class Replaced(val loom: InMemoryLoom, val host: GameSession, val afterReplacement: ClusterConfig)

    /**
     * Seats [seatedBeforeLoss] voters of [peerCount] (host included), kills Xavier, waits for the
     * host to evict him, and seats Yasmin in his place.
     *
     * Xavier connects alone and is seated first, so he is seated before the host returns and the host
     * watches him; a voter the latecomer loop seats may have no detector (#3114). The others connect
     * once he holds his seat. His session runs on its own scope, which is cancelled before his seam
     * closes, so his device is gone rather than silent: no admission loop can seat him again.
     */
    private suspend fun TestScope.replacedOnce(peerCount: Int, returnAt: ReturnPolicy, seatedBeforeLoss: Int): Replaced {
        val loom = InMemoryLoom()
        val (hostRaw, xRaw) = seats(loom, 2)
        val xavier = NodeId(xRaw.selfId.value)
        val liveness = fastLivenessConfig()
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = peerCount,
                returnAt = returnAt,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = liveness,
                clock = clock,
            )
        }
        val xScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        within("Xavier is admitted") { xScope.gameJoin(xRaw, raftConfig = fastRaftConfig(seed = 2L)) }
        val otherJoins = (2 until seatedBeforeLoss).map { i ->
            val raw = loom.join(InMemoryTag("early-$i"))
            async { backgroundScope.gameJoin(raw, raftConfig = fastRaftConfig(seed = 1L + i)) }
        }
        val host = within("the host returns") { hostDeferred.await() }
        otherJoins.forEach { within("an early voter is admitted") { it.await() } }
        within("every early voter is seated") { host.node.membership.first { it.voters.size == seatedBeforeLoss } }
        val monitor = assertNotNull(host.livenessMonitor, "rig: the host monitors its voters")
        assertNotNull(monitor.detectorJobForTest(xavier), "rig: a detector watches Xavier")
        val presence = assertNotNull(host.lobbyPresence, "rig: the host publishes presence")
        if (seatedBeforeLoss == peerCount) {
            within("rig: a full roster closes admission") { presence.admissionClosed.filterNotNull().first() }
        } else {
            assertNull(presence.admissionClosed.value, "rig: a short roster leaves admission open")
        }

        val evicted = async { host.node.membership.first { xavier !in it.voters } }
        runCurrent()
        xScope.cancel()
        xRaw.close()
        val lossMs = (liveness.timeout + liveness.reconnectWindow + liveness.interval * 4).inWholeMilliseconds
        advanceTimeBy(lossMs)
        runCurrent()
        within("Xavier is evicted once lost") { evicted.await() }

        val yRaw = loom.join(InMemoryTag("yasmin"))
        val yasmin = NodeId(yRaw.selfId.value)
        within("Yasmin is seated") { backgroundScope.gameJoin(yRaw, raftConfig = fastRaftConfig(seed = 7L)) }
        val afterReplacement = settledMembership(host.node)
        assertTrue(yasmin in afterReplacement.voters, "rig: Yasmin takes Xavier's seat: $afterReplacement")
        assertTrue(xavier !in afterReplacement.voters, "rig: Xavier stays out: $afterReplacement")
        return Replaced(loom, host, afterReplacement)
    }

    /** Waits for [block] at most [WAIT_BOUND] of virtual time, failing as [what] if it does not finish. */
    private suspend fun <T : Any> within(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(WAIT_BOUND) { block() } ?: fail("$what, within $WAIT_BOUND of virtual time")

    private companion object {
        /** A virtual-time bound: `withTimeoutOrNull` under `runTest` measures the test scheduler. */
        val WAIT_BOUND: Duration = 5.seconds
    }
}
