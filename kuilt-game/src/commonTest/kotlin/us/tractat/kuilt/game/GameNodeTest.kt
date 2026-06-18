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
import us.tractat.kuilt.raft.RaftNode
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

    /**
     * Validates the latecomer admission property: a voter admitted last (after the host has
     * already formed a quorum with the first joiner) can replay every committed action from the
     * log via [RaftNode.committedFrom].
     *
     * **D2 readiness-gate resolution:** [gameHost] with `peerCount = 3` returns only after full
     * membership (voters.size == peerCount), not at quorum-2. There is no API surface to propose
     * before the third voter joins. The latecomer property is therefore validated by confirming
     * that the *last-admitted* voter (joiner2 — admitted after joiner1, and potentially after
     * the host's no-op commit) can replay an action proposed once full membership is reached.
     * Return-at-quorum (D2) is a deliberate future option, not built in Task 4.
     *
     * [committedFrom] replays raw log entries including config entries (`entry.config != null`)
     * that the live [RaftNode.committed] flow withholds. Filter these before decoding application
     * entries — see the [hostAdmitsOneJoiner] test for the canonical pattern.
     */
    @Test
    fun latecomerJoinsAfterFirstCommit() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, j1, j2) = seats(loom, 3)

        // Launch all three concurrently: host's admit loop blocks until voters.size == 3;
        // both joiners must be running so they can receive the membership change commits.
        // Use backgroundScope receiver so RaftNode lifetime ties to backgroundScope, not async.
        val hostDeferred = async { backgroundScope.gameHost(hostSeam, peerCount = 3, raftConfig = fastRaftConfig(seed = 1L)) }
        val j1Deferred = async { backgroundScope.gameJoin(j1, raftConfig = fastRaftConfig(seed = 2L)) }
        val j2Deferred = async { backgroundScope.gameJoin(j2, raftConfig = fastRaftConfig(seed = 3L)) }

        val host = hostDeferred.await()
        val joiner1 = j1Deferred.await()
        val joiner2 = j2Deferred.await()

        // All three voters are now admitted. Propose an action on the host (leader).
        val entry = TurnSequencer(host, Int.serializer()).propose(5)
        assertEquals(5, entry.action)

        // Both joiners replay the committed action from the log.
        // joiner2 is the latecomer that may have missed earlier log entries as a non-voter;
        // committedFrom(1) guarantees replay of all committed entries from index 1 onward.
        // committedFrom replays raw log entries including config entries (entry.config != null)
        // that the live committed flow withholds — filter them before decoding application entries.
        fun appEntries(node: RaftNode) =
            node.committedFrom(1).mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val logEntry = committed.entry
                if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
            }

        val j1Action = appEntries(joiner1).first()
        val j2Action = appEntries(joiner2).first()

        assertEquals(5, j1Action, "joiner1 must replay the committed action")
        assertEquals(5, j2Action, "joiner2 (latecomer) must replay the committed action")
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
