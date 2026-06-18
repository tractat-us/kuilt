@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.raft.Committed
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
    fun hostAdmitsOneJoiner() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)

        // gameHost and gameJoin both suspend until membership settles — launch them concurrently
        // so the host's admit loop can process the joiner while the joiner awaits its admission
        // signal. The CoroutineScope receiver must be backgroundScope so the RaftNode lifetime is
        // tied to backgroundScope (not to the async block), allowing the async to complete once
        // the suspend work is done while nodes keep running on backgroundScope.
        val hostDeferred = async { backgroundScope.gameHost(hostSeam, peerCount = 2, raftConfig = fastRaftConfig(seed = 1L)) }
        val joinDeferred = async { backgroundScope.gameJoin(joinSeam, raftConfig = fastRaftConfig(seed = 2L)) }

        val host = hostDeferred.await()
        val joiner = joinDeferred.await()

        // Host is the leader; propose an action.
        val entry = TurnSequencer(host, Int.serializer()).propose(99)
        assertEquals(99, entry.action)

        // Observe the committed action on the joiner. committed is a hot flow (replay=0),
        // so we use committedFrom(1) which replays already-committed entries — safe to call
        // after the proposal without missing the entry.
        // committedFrom replays raw log entries including config entries (entry.config != null);
        // those are withheld from the live committed flow but surface in replay. Use mapNotNull
        // to skip them and decode only application entries.
        val joinerAction = joiner.committedFrom(1)
            .mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val logEntry = committed.entry
                if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
            }
            .first()
        assertEquals(99, joinerAction)
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
