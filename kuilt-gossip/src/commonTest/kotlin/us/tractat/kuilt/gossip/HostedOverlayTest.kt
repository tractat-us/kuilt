package us.tractat.kuilt.gossip

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class HostedOverlayTest {

    /** A connection offered AFTER the hub has started still joins — the accept-pump admits it. */
    @Test
    fun lateJoinedConnectionJoinsRunningHub() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
        val source = InMemoryConnectionSource()

        // Start the hub with no connections yet.
        val hub = backgroundScope.hostedOverlay(PeerId("hub"), source, dispatcher, Random(0L), clock)

        // A client connects after the hub is already running.
        val (hubEnd, clientEnd) = connectionPair()
        val clientBuild = backgroundScope.async {
            GossipSeam(meshSeam(PeerId("client-0"), listOf(clientEnd), dispatcher), Random(1L), clock)
                .also { it.start(backgroundScope) }
        }
        source.offer(hubEnd)            // pump accepts → addLink on the running hub
        val client = clientBuild.await()

        // Both sides see each other once the handshake crosses.
        hub.peers.first { PeerId("client-0") in it }
        client.peers.first { PeerId("hub") in it }
        assertTrue(PeerId("client-0") in hub.peers.value)
    }
}
