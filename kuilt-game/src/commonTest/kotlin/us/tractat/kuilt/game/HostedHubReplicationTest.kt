@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.fabric.inMemoryStarOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Gating integration test for the hosted-game hub: prove **prompt, in-order** RGA chat
 * forward-flow under [gameHost] over a star-shaped `meshSeam`.
 *
 * This is the first test to drive [gameHost]/[gameJoin] over a star where the hub is the only
 * relay (FullFanout `GossipSeam`). A client appends three chat messages; the host and the other
 * two clients must converge to `[m0, m1, m2]` in order well within the bounded virtual-time
 * window — far under the 60 s anti-entropy interval. Converging that fast proves the hub's
 * FullFanout relay forwards traffic promptly, not that slow anti-entropy eventually repairs it.
 */
class HostedHubReplicationTest {

    @Test
    fun clientChatRgaReachesAllOtherClientsPromptly() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val star = backgroundScope.inMemoryStarOf(n = 3)
        advanceTimeBy(300)
        runCurrent()

        // Host on the hub seam; each client joins on its own seam. The host's admit loop and the
        // joiners' admission waits must run concurrently, so launch them all before awaiting.
        val hostDeferred = async {
            backgroundScope.gameHost(star.hub, peerCount = 4, raftConfig = fastRaftConfig(seed = 1L))
        }
        val joinDeferreds = star.clients.mapIndexed { i, client ->
            async { backgroundScope.gameJoin(client, raftConfig = fastRaftConfig(seed = (i + 2).toLong())) }
        }
        advanceTimeBy(2000)
        runCurrent()

        val hostSession = hostDeferred.await()
        val clientSessions = joinDeferreds.map { it.await() }

        val hostChat = chatQuilter(hostSession, backgroundScope)
        val clientChats = clientSessions.map { chatQuilter(it, backgroundScope) }
        advanceTimeBy(300)
        runCurrent()

        // Client 0 appends m0, m1, m2 at the tail so order is preserved.
        repeat(3) { k ->
            val current = clientChats[0].state.value
            val (_, op) = current.insertAt(
                replica = clientChats[0].replica,
                index = current.toList().size,
                value = "m$k",
            )
            clientChats[0].apply(Patch(Rga.empty<String>().apply(op)))
        }
        advanceTimeBy(2000)
        runCurrent()

        val expected = listOf("m0", "m1", "m2")
        assertEquals(expected, hostChat.state.value.toList(), "host did not converge")
        assertEquals(expected, clientChats[1].state.value.toList(), "client 1 did not converge")
        assertEquals(expected, clientChats[2].state.value.toList(), "client 2 did not converge")
    }

    private fun chatQuilter(
        session: GameSession,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Quilter<Rga<String>> =
        Quilter(
            seam = session.appChannel("chat"),
            initial = Rga.empty(),
            valueSerializer = Rga.wireSerializer(String.serializer()),
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true),
        )
}
