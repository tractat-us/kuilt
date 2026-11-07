package us.tractat.kuilt.cluster

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Concurrent learner admission through [ServerCluster] (#3120): admitting a second learner must never
 * evict the first.
 *
 * `admitLearner` used to build each admission's target from the cluster's *constructed* config,
 * `voters + {this learner}`, and `changeMembershipWithRetry` resent that stale target once the
 * previous change committed. So every admission replaced the learner set, and an earlier learner
 * stopped receiving replication while its own proposals still committed.
 *
 * Every assertion is about the learners **receiving**: membership is necessary, and only a learner's
 * applied log shows that the leader is still replicating to it. Harness: [ServerClusterSim].
 */
class ServerClusterConcurrentAdmissionTest {

    /** The shape #3120 reported: two connections admitted at the same instant, each by its own writer. */
    @Test
    fun twoConcurrentAdmissionsKeepBothLearners() = admissionTest(learnerCount = 2) { h ->
        h.startRelay()
        h.awaitAllAdmitted()
        h.proposeAndAwaitCrossVisibility()
        h.assertAllStillAdmitted()
    }

    /**
     * Twenty arrivals spread over a second, so they straddle several membership changes: some land
     * while a change is in flight and are batched into the next, and every later change must keep
     * the learners an earlier one committed. Arrivals at one instant would be admitted in a single
     * change, where replacing the learner set and adding to it cannot be told apart.
     */
    @Test
    fun aStormOfTwentyAdmissionsKeepsEveryLearner() = admissionTest(learnerCount = 20, arrivalGap = 50.milliseconds) { h ->
        h.startRelay()
        h.awaitAllAdmitted()
        h.proposeAndAwaitCrossVisibility()
        h.assertAllStillAdmitted()
    }

    /**
     * Leadership moves while the storm is being admitted, the shape of #3038: the admission must
     * re-read the new leader's membership rather than resend a target built against the old one, and
     * must not give a learner up because the leader it was talking to stepped down.
     */
    @Test
    fun leadershipTransferMidStormStillAdmitsEveryLearner() = admissionTest(
        learnerCount = 20,
        // Arrivals spread over two seconds, so the transfer below lands while most are still coming:
        // at one instant the reconciler would batch all twenty into one change before it started.
        arrivalGap = 100.milliseconds,
    ) { h ->
        val first = h.sim.awaitLeader(among = h.voters)
        h.startRelay()
        h.sim.awaitTrue("the storm has started committing", within = 5.seconds) {
            first.membership.value.learners.isNotEmpty()
        }
        val admittedAtTransfer = first.membership.value.learners.size
        val target = h.voters.first { h.sim.nodes.getValue(it) !== first }
        backgroundScope.launch {
            try {
                first.transferLeadership(target)
            } catch (_: Throwable) {
                // A transfer that cannot be confirmed in its window is not what is under test: the
                // assertion below is that admission converges under whichever leader emerges.
                currentCoroutineContext().ensureActive()
            }
        }
        h.sim.awaitTrue("leadership has left the first leader", within = 5.seconds) {
            h.sim.leader()?.let { it !== first } == true
        }
        // The rig fired: the transfer began with the storm unfinished, or this arm tests nothing.
        assertTrue(
            admittedAtTransfer < h.learners.size,
            "the transfer must start mid-storm; $admittedAtTransfer of ${h.learners.size} were already admitted",
        )
        h.awaitAllAdmitted()
        h.proposeAndAwaitCrossVisibility()
        h.assertAllStillAdmitted()
    }

    private fun admissionTest(
        learnerCount: Int,
        arrivalGap: Duration = Duration.ZERO,
        body: suspend TestScope.(ServerClusterSim) -> Unit,
    ) = serverClusterSimTest(learnerCount, LearnerRemoval.Never, arrivalGap, body)

    private suspend fun ServerClusterSim.awaitAllAdmitted() =
        awaitLeaderLearners(learners.toSet(), "every learner is in the leader's membership")

    /** Admission is settled, not merely reached once: an evicting retry would land after it. */
    private suspend fun ServerClusterSim.assertAllStillAdmitted() {
        delay(SETTLE)
        val leader = sim.awaitLeader(among = voters)
        val behind = behindTheLeader(learners)
        assertAll(
            { assertEquals(learners.toSet(), leader.membership.value.learners, "no learner was evicted") },
            { assertEquals(voters, leader.membership.value.voters, "admission never touches the voter set") },
            { assertTrue(behind.isEmpty(), "every learner has applied exactly the leader's log; behind: $behind") },
        )
    }

    private companion object {
        /** A quiet stretch after convergence, long enough for any stale retry to have landed. */
        val SETTLE = 5.seconds
    }
}
