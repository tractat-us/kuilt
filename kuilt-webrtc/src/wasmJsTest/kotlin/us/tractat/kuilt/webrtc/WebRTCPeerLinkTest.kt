package us.tractat.kuilt.webrtc

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.webrtc.internal.WebRTCPeerLink
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRTCPeerLinkTest {
    @Test
    fun broadcastSendsBytesViaFacade() =
        runTest {
            val (hostFac, joinerFac) = PairedFacadeFactory.pair()
            val host = hostFac.create(IceConfig.NoServers, hostInitiated = true)
            val joiner = joinerFac.create(IceConfig.NoServers, hostInitiated = false)

            val selfId = PeerId("self")
            val remoteId = PeerId("remote")
            val link = WebRTCPeerLink(selfId = selfId, remoteId = remoteId, facade = host)

            link.broadcast("hello".encodeToByteArray())
            val received = joiner.incomingBytes.first()
            assertContentEquals("hello".encodeToByteArray(), received)
        }

    @Test
    fun incomingFlowEmitsRemoteBytesWithSenderStamp() =
        runTest {
            val (hostFac, joinerFac) = PairedFacadeFactory.pair()
            val host = hostFac.create(IceConfig.NoServers, hostInitiated = true)
            val joiner = joinerFac.create(IceConfig.NoServers, hostInitiated = false)

            val selfId = PeerId("self")
            val remoteId = PeerId("remote")
            val hostLink = WebRTCPeerLink(selfId = selfId, remoteId = remoteId, facade = host)

            joiner.sendBytes("ping".encodeToByteArray())
            val frame = hostLink.incoming.first()
            assertContentEquals("ping".encodeToByteArray(), frame.payload)
            assertEquals(remoteId, frame.sender)
            assertTrue(frame.sequence >= 0)
        }

    @Test
    fun peerSetIncludesBothPeers() =
        runTest {
            val (hostFac, _) = PairedFacadeFactory.pair()
            val host = hostFac.create(IceConfig.NoServers, hostInitiated = true)
            val selfId = PeerId("self")
            val remoteId = PeerId("remote")
            val link = WebRTCPeerLink(selfId = selfId, remoteId = remoteId, facade = host)
            assertEquals(setOf(selfId, remoteId), link.peers.value)
        }

    @Test
    fun closeIsIdempotent() =
        runTest {
            val (hostFac, _) = PairedFacadeFactory.pair()
            val host = hostFac.create(IceConfig.NoServers, hostInitiated = true)
            val link =
                WebRTCPeerLink(
                    selfId = PeerId("self"),
                    remoteId = PeerId("remote"),
                    facade = host,
                )
            link.close()
            link.close() // must not throw
        }
}
