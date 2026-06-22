@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.raft.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [GameSession] — the return type of [gameNode]/[gameHost]/[gameJoin]. Verifies that
 * named application channels ride the same fabric as Raft consensus without interfering, and that
 * [GameSession.close] tears the session down idempotently.
 *
 * Virtual time via [StandardTestDispatcher]; consensus is driven through the canonical [seats]
 * harness with per-node seeded [fastRaftConfig].
 *
 * App-channel delivery is best-effort (`replay = 0`): a frame sent before the receiver subscribes
 * is lost. Each test subscribes via [produceIn] on [backgroundScope] and pumps the scheduler with
 * `runCurrent()` so the receiver is collecting *before* the broadcast — deterministic, no race.
 */
class GameSessionTest {

    @Test
    fun appChannelRoundTripsHostJoiner() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)

        val hostDeferred = async { backgroundScope.gameHost(hostSeam, peerCount = 2, raftConfig = fastRaftConfig(seed = 1L)) }
        val joinDeferred = async { backgroundScope.gameJoin(joinSeam, raftConfig = fastRaftConfig(seed = 2L)) }
        val host = hostDeferred.await()
        val joiner = joinDeferred.await()

        val chat = joiner.appChannel("chat").incoming.produceIn(backgroundScope)
        runCurrent()
        host.appChannel("chat").broadcast(byteArrayOf(7, 8, 9))
        assertTrue(chat.receive().toByteArray().contentEquals(byteArrayOf(7, 8, 9)), "chat frame must round-trip")

        // Consensus still works alongside the app traffic.
        val move = TurnSequencer(host.node, Int.serializer()).propose(42)
        assertEquals(42, move.action)
    }

    @Test
    fun raftUnaffectedByConcurrentAppTraffic() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)
        val voters = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))

        val a = backgroundScope.gameNode(hostSeam, voters, raftConfig = fastRaftConfig(seed = 1L))
        val b = backgroundScope.gameNode(joinSeam, voters, raftConfig = fastRaftConfig(seed = 2L))

        // Pour app traffic onto "chat" while the cluster elects + commits.
        a.appChannel("chat").broadcast(byteArrayOf(1))
        b.appChannel("chat").broadcast(byteArrayOf(2))

        val leaderNode = awaitEitherLeader(a.node, b.node)
        val move = TurnSequencer(leaderNode, Int.serializer()).propose(100)
        assertEquals(100, move.action, "raft must reach agreement despite concurrent app-channel traffic")

        // App traffic after election still flows between the two sessions.
        val leader = if (leaderNode === a.node) a else b
        val other = if (leader === a) b else a
        val received = other.appChannel("chat").incoming.produceIn(backgroundScope)
        runCurrent()
        leader.appChannel("chat").broadcast(byteArrayOf(55))
        assertTrue(received.receive().toByteArray().contentEquals(byteArrayOf(55)))
    }

    @Test
    fun sameNameConvergesDifferentNamesIsolated() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)
        val voters = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))

        val a = backgroundScope.gameNode(hostSeam, voters, raftConfig = fastRaftConfig(seed = 1L))
        val b = backgroundScope.gameNode(joinSeam, voters, raftConfig = fastRaftConfig(seed = 2L))

        // Subscribe both names on b before any broadcast; "cursors" must stay empty.
        val cursors = b.appChannel("cursors").incoming.produceIn(backgroundScope)
        val chat = b.appChannel("chat").incoming.produceIn(backgroundScope)
        runCurrent()

        a.appChannel("chat").broadcast(byteArrayOf(1))
        assertTrue(chat.receive().toByteArray().contentEquals(byteArrayOf(1)), "same name converges across peers")
        assertTrue(cursors.tryReceive().isFailure, "a frame on \"chat\" must not reach \"cursors\"")
    }

    @Test
    fun closeStopsNodeAndSeamIdempotently() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)
        val voters = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))

        val a = backgroundScope.gameNode(hostSeam, voters, raftConfig = fastRaftConfig(seed = 1L))
        val b = backgroundScope.gameNode(joinSeam, voters, raftConfig = fastRaftConfig(seed = 2L))

        // Let the cluster come up so close() tears down a live node.
        awaitEitherLeader(a.node, b.node)

        a.close()
        assertIs<SeamState.Torn>(hostSeam.state.value, "close() must tear the fabric down")

        // Second close is a no-op — must not throw.
        a.close()
        assertIs<SeamState.Torn>(hostSeam.state.value)
    }
}
