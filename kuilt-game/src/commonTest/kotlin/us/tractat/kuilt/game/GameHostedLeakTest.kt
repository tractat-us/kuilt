@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Instant

class GameHostedLeakTest {

    /**
     * A per-seat `sendTo(client-0, …)` is delivered to the addressed seat and never relayed by
     * FullFanout to any other seat. Both halves are asserted: the addressed seat receives exactly
     * the bytes sent, and the bystander seat receives nothing.
     */
    @Test
    fun perSeatSendToIsNeverRelayed() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
        val source = InMemoryConnectionSource()

        // Build the hub overlay + two clients (use hostedOverlay directly — gameHost not needed to
        // pin the wire-level invariant).
        val clientEnds = (0..1).map { i ->
            val (hubEnd, clientEnd) = connectionPair()
            source.offer(hubEnd)
            PeerId("client-$i") to clientEnd
        }
        val hub = backgroundScope.hostedOverlay(PeerId("hub"), source, dispatcher, Random(0L), clock)
        val clients = clientEnds.map { (id, conn) ->
            id to GossipSeam(meshSeam(id, listOf(conn), dispatcher), Random(id.value.hashCode().toLong()), clock)
                .also { it.start(backgroundScope) }
        }
        hub.peers.first { clients.all { (id, _) -> id in it } }   // converged

        // Collect what BOTH clients receive.
        val seenByZero = mutableListOf<ByteArray>()
        val seenByOne = mutableListOf<ByteArray>()
        val c0 = backgroundScope.async {
            clients[0].second.incoming.collect { seenByZero += it.toByteArray() }
        }
        val c1 = backgroundScope.async {
            clients[1].second.incoming.collect { seenByOne += it.toByteArray() }
        }

        hub.sendTo(PeerId("client-0"), byteArrayOf(42))               // disclosure for seat 0 only
        runCurrent()

        assertAll(
            { assertEquals(1, seenByZero.size, "client-0 (the addressed seat) must receive the disclosure") },
            { assertContentEquals(byteArrayOf(42), seenByZero.single(), "client-0 receives the exact bytes") },
            { assertEquals(0, seenByOne.size, "client-1 must never observe a frame addressed to client-0") },
        )
        c0.cancel()
        c1.cancel()
    }
}
