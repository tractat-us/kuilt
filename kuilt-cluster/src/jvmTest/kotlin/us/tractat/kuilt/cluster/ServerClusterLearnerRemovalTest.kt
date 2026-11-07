package us.tractat.kuilt.cluster

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Learner removal after a grace (#3120): a [ServerCluster] built with a `learnerGrace` removes a
 * learner once it has been disconnected for the whole grace, keeps one that reconnects under the
 * same id inside it, and re-admits one that reconnects after it. Harness: [ServerClusterSim].
 *
 * That a learner was never absent across a reconnect is pinned in [LearnerGraceTest], which records
 * every change attempted; sampling a sim's membership cannot see a removal and re-admission that
 * happen inside one virtual instant.
 */
class ServerClusterLearnerRemovalTest {

    private val grace = 2.seconds

    @Test
    fun aLearnerGoneForTheGraceIsRemovedAndTheRestKeepReceiving() = removalTest { h ->
        val (gone, stay1, stay2) = h.learners
        h.startRelay()
        h.awaitLeaderLearners(h.learners.toSet(), "all three admitted")
        h.disconnect(gone)
        delay(grace - 200.milliseconds)
        val insideGrace = h.leaderLearners()
        h.awaitLeaderLearners(setOf(stay1, stay2), "the departed learner is removed after its grace")
        h.proposeAndAwaitCrossVisibility(among = listOf(stay1, stay2), tag = 1)
        val leader = h.sim.awaitLeader(among = h.voters)
        val behind = h.behindTheLeader(listOf(stay1, stay2))

        assertAll(
            { assertEquals(h.learners.toSet(), insideGrace, "kept while its grace runs") },
            { assertEquals(h.voters, leader.membership.value.voters, "removal never touches the voter set") },
            { assertTrue(behind.isEmpty(), "the learners that stayed have the leader's whole log; behind: $behind") },
        )
    }

    @Test
    fun aReconnectInsideTheGraceKeepsTheLearnerAndItKeepsReceiving() = removalTest { h ->
        val (flaky, other, _) = h.learners
        h.startRelay()
        h.awaitLeaderLearners(h.learners.toSet(), "all three admitted")
        h.disconnect(flaky)
        delay(grace / 2)
        h.connect(flaky)
        delay(grace * 3)
        val afterReconnect = h.leaderLearners()
        h.proposeAndAwaitCrossVisibility(among = listOf(flaky, other), tag = 1)

        // A flap: it drops again, and keeps a whole grace from this disconnect.
        h.disconnect(flaky)
        delay(grace - 200.milliseconds)
        val insideSecondGrace = h.leaderLearners()

        assertAll(
            { assertEquals(h.learners.toSet(), afterReconnect, "a reconnect inside the grace is never removed") },
            { assertEquals(h.learners.toSet(), insideSecondGrace, "a second disconnect gets a full grace of its own") },
        )
        h.awaitLeaderLearners(h.learners.toSet() - flaky, "then that grace removes it", within = grace)
    }

    @Test
    fun aLearnerReadmittedAfterRemovalCatchesUpTheWholeLog() = removalTest { h ->
        val (returning, stay1, stay2) = h.learners
        h.startRelay()
        h.awaitLeaderLearners(h.learners.toSet(), "all three admitted")
        h.disconnect(returning)
        h.awaitLeaderLearners(setOf(stay1, stay2), "removed after its grace", within = grace * 3)
        // Entries the returning learner was not there for.
        h.proposeAndAwaitCrossVisibility(among = listOf(stay1, stay2), tag = 1)

        h.connect(returning)
        h.awaitLeaderLearners(h.learners.toSet(), "re-admitted on reconnect")
        h.proposeAndAwaitCrossVisibility(tag = 2)
        val behind = h.behindTheLeader(h.learners)
        assertTrue(behind.isEmpty(), "every learner, the returning one included, has the leader's log; behind: $behind")
    }

    private fun removalTest(body: suspend TestScope.(ServerClusterSim) -> Unit) =
        serverClusterSimTest(learnerCount = 3, learnerRemoval = LearnerRemoval.AfterGrace(grace), body = body)
}
