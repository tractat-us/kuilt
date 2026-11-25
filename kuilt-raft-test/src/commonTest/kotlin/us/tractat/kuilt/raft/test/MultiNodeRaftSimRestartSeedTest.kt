@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.test

import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A restart through [MultiNodeRaftSim]'s default factory is a new process: it gets a new seed, so a
 * [us.tractat.kuilt.raft.ClientIdentity.Auto] node comes back under a new client id (#3425). The
 * seed is still a pure function of the sim seed, the node and its start count.
 *
 * The forward arm needs the **same leader** on both sides of the restart, because the collision it
 * guards against is the leader's cached result for the old incarnation's `(clientId, requestId)`.
 * A new leader holds no cache and would let a reused id through, so the arm asserts the leader did
 * not move rather than assuming it.
 *
 * Both sim arms propose **at once** after a restart, before the node has caught up, as a governed
 * heddle peer does with its enroll. Waiting first is the vacuous configuration: a caught-up `Auto`
 * node finds its old id in the replicated log, takes it as a collision and re-mints, so every arm
 * here stayed green with the seed reused on every start. Proposing first, both red with
 * `RequestIdConflictException` on that mutant.
 */
class MultiNodeRaftSimRestartSeedTest {

    @Test
    fun aFollowerRestartedThroughTheDefaultFactoryForwardsUnderAFreshClientId() = raftSimTest(n = 3) { sim ->
        val leaderId = sim.awaitLeaderId()
        val followerId = sim.nodeIds.first { it != leaderId }
        val before = proposeOn(sim, followerId, "before the restart")
        val oldNode = sim.nodes.getValue(followerId)

        sim.crash(followerId)
        sim.restart(followerId)
        // RIG (b): catch up before proposing, so a reused seed's collision re-mints first.
        sim.awaitTrue("the restarted node learned the old forward's commit") {
            sim.nodes.getValue(followerId).commitIndex.value >= before.index
        }
        val after = proposeOn(sim, followerId, "after the restart")
        val leaderAfter = sim.awaitLeaderId()

        assertAll(
            { assertNotSame(oldNode, sim.nodes.getValue(followerId), "the rig: the node was restarted") },
            { assertEquals(leaderId, leaderAfter, "the rig: the leader that cached the old forward still leads") },
            { assertEquals(1L, before.requestIdOf(), "the rig: the old incarnation used serial 1") },
            { assertEquals(1L, after.requestIdOf(), "the rig: the new incarnation reuses serial 1") },
            { assertNotEquals(before.clientIdOf(), after.clientIdOf(), "the restart minted a new client id") },
            {
                assertEquals(
                    ClientId.auto(followerId, Random(FIRST_START_SEEDS.getValue(sim.nodeIds.indexOf(followerId)))),
                    before.clientIdOf(),
                    "the rig: the first incarnation forwarded under the first draw from its first-start seed",
                )
            },
            {
                assertEquals(
                    ClientId.auto(followerId, Random(FIRST_RESTART_SEEDS.getValue(sim.nodeIds.indexOf(followerId)))),
                    after.clientIdOf(),
                    "the restarted node forwarded under the first draw from its restart seed, not a re-mint",
                )
            },
            { assertTrue(after.index > before.index, "the forward committed after the old one: $before then $after") },
        )
        sim.awaitCommit(after.index)
    }

    @Test
    fun oneSeedReplaysTheSameIdsAcrossRestartsAndEachStartDrawsItsOwn() =
        runTest(StandardTestDispatcher(), timeout = RAFT_SIM_WEDGE_BACKSTOP) {
            val first = clientIdsAcrossRestarts(restarts = 2)
            val second = clientIdsAcrossRestarts(restarts = 2)
            assertAll(
                { assertEquals(3, first.size, "the rig: one id per start, three starts: $first") },
                { assertEquals(first, second, "one sim seed, one run") },
                { assertEquals(first.size, first.toSet().size, "every start drew its own id: $first") },
            )
        }

    @Test
    fun aFirstStartKeepsItsSeedAndEachRestartMovesOff() {
        // Worked by hand from `baseSeed + nodeIndex + start * -0x61c8864680b583eb`, wrapping at 2^64.
        val starts = (0..3).map { simNodeSeed(MULTI_NODE_SIM_SEED, nodeIndex = 2, start = it) }
        val firstStarts = (0..4).map { simNodeSeed(MULTI_NODE_SIM_SEED, nodeIndex = it, start = 0) }
        assertAll(
            { assertEquals(487L, simNodeSeed(baseSeed = 485L, nodeIndex = 2, start = 0), "a first start keeps baseSeed + nodeIndex") },
            { assertEquals(-7046029254386352644L, simNodeSeed(baseSeed = 485L, nodeIndex = 2, start = 1), "a first restart") },
            { assertEquals(4354685564936845841L, simNodeSeed(baseSeed = 485L, nodeIndex = 2, start = 2), "a second restart wraps") },
            { assertEquals(-2691343689449507777L, simNodeSeed(baseSeed = 0L, nodeIndex = 0, start = 3), "a third restart wraps twice") },
            { assertEquals(starts.size, starts.toSet().size, "each start of one node has its own seed: $starts") },
            { assertTrue(starts.drop(1).none { it in firstStarts }, "no restart lands on a node's first seed") },
        )
    }

    /** Boot a 3-node sim on the default seed and record one follower's client id at each of its starts. */
    private suspend fun TestScope.clientIdsAcrossRestarts(restarts: Int): List<ClientId> {
        val ids = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val leaderId = sim.awaitLeaderId()
        val followerId = ids.first { it != leaderId }
        val seen = mutableListOf(proposeOn(sim, followerId, "start 0").clientIdOf())
        for (start in 1..restarts) {
            sim.crash(followerId)
            sim.restart(followerId)
            seen += proposeOn(sim, followerId, "start $start").clientIdOf()
        }
        ids.forEach(sim::crash) // so this sim's timers stop before the next one boots
        return seen
    }

    private suspend fun MultiNodeRaftSim.awaitLeaderId(): NodeId {
        awaitLeader()
        return nodeIds.first { nodes[it]?.role?.value is RaftRole.Leader }
    }

    /** Propose [command] on node [id] (a follower forwards it) and wait, bounded, for the commit. */
    private suspend fun TestScope.proposeOn(sim: MultiNodeRaftSim, id: NodeId, command: String): LogEntry {
        val node = sim.nodes.getValue(id)
        val proposal = backgroundScope.async { node.propose(command.encodeToByteArray()) }
        sim.awaitTrue("$id's proposal '$command' completes") { proposal.isCompleted }
        return proposal.await()
    }

    private fun LogEntry.clientIdOf(): ClientId = checkNotNull(dedupKey) { "no dedup key on $this" }.clientId

    private fun LogEntry.requestIdOf(): Long = checkNotNull(dedupKey) { "no dedup key on $this" }.requestId

    private companion object {
        /** `MULTI_NODE_SIM_SEED + nodeIndex`, by node index: each node's first-start seed in a default sim. */
        val FIRST_START_SEEDS: Map<Int, Long> = mapOf(0 to 485L, 1 to 486L, 2 to 487L)

        /** Each node's first-restart seed in a default sim, worked by hand as in the literal test above. */
        val FIRST_RESTART_SEEDS: Map<Int, Long> =
            mapOf(0 to -7046029254386352646L, 1 to -7046029254386352645L, 2 to -7046029254386352644L)
    }
}
