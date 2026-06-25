package us.tractat.kuilt.gossip

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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

    /**
     * A torn spoke (client-end closed before the Hello preamble completes) must NOT kill the
     * accept-pump. The next healthy spoke offered after the failed one must still join the hub.
     *
     * Without the `runCatchingCancellable` guard a thrown exception from `addLink` propagates
     * out of the pump coroutine, cancelling it permanently — the good spoke never reaches
     * `hubMesh.addLink` and the hub's peer set never includes `client-good`.
     */
    @Test
    fun tornSpokeBeforeHandshakeDoesNotKillAcceptPump() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
        val source = InMemoryConnectionSource()

        val hub = backgroundScope.hostedOverlay(PeerId("hub"), source, dispatcher, Random(0L), clock)

        // Offer a bad spoke: close the client-end immediately so the hub-side addLink sees a
        // closed incoming flow during the MeshHello preamble read → throws → pump must survive.
        val (badHubEnd, badClientEnd) = connectionPair()
        badClientEnd.close()            // tears the spoke before it can send a Hello
        source.offer(badHubEnd)         // pump dequeues it, addLink throws, pump must continue
        runCurrent()                    // let the pump attempt (and fail) the bad admit

        // Now offer a healthy spoke — the pump must still be alive to accept it.
        val (goodHubEnd, goodClientEnd) = connectionPair()
        val goodClientBuild = backgroundScope.async {
            GossipSeam(meshSeam(PeerId("client-good"), listOf(goodClientEnd), dispatcher), Random(2L), clock)
                .also { it.start(backgroundScope) }
        }
        source.offer(goodHubEnd)
        goodClientBuild.await()

        hub.peers.first { PeerId("client-good") in it }
        assertTrue(PeerId("client-good") in hub.peers.value)
    }
}
