package us.tractat.kuilt.game

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.raft.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class GameNodeTest {

    @Test
    fun rosterGivenTwoPeersConverge() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)
        val voters = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))

        val a = backgroundScope.gameNode(hostSeam, voters, raftConfig = fastRaftConfig(seed = 1L))
        val b = backgroundScope.gameNode(joinSeam, voters, raftConfig = fastRaftConfig(seed = 2L))

        val leader = awaitEitherLeader(a, b)
        val proposed = TurnSequencer(leader, Int.serializer()).propose(42)
        assertEquals(42, proposed.action)
    }

    @Test
    fun rosterGivenThreePeersQuorumTwo() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seams = seats(loom, 3)
        val voters = seams.map { NodeId(it.selfId.value) }.toSet()
        val (s0, s1, s2) = seams
        val nodes = listOf(
            backgroundScope.gameNode(s0, voters, raftConfig = fastRaftConfig(seed = 1L)),
            backgroundScope.gameNode(s1, voters, raftConfig = fastRaftConfig(seed = 2L)),
            backgroundScope.gameNode(s2, voters, raftConfig = fastRaftConfig(seed = 3L)),
        )

        val leader = awaitAnyLeader(nodes)
        val entry = TurnSequencer(leader, Int.serializer()).propose(7)
        assertEquals(7, entry.action)
    }
}
