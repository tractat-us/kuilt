package us.tractat.kuilt.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.webrtc.internal.WebRTCPeerLink
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            assertContentEquals("ping".encodeToByteArray(), frame.toByteArray())
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
    fun rosterReconcilesResolvedRemoteIdAndSendToSucceeds() =
        runTest {
            val (hostFac, joinerFac) = PairedFacadeFactory.pair()
            val host = hostFac.create(IceConfig.NoServers, hostInitiated = true)
            val joiner = joinerFac.create(IceConfig.NoServers, hostInitiated = false)

            val selfId = PeerId("self")
            val placeholder = PeerId("placeholder")
            val resolved = PeerId("real-remote")
            val senderId = CompletableDeferred<PeerId>()
            val link =
                WebRTCPeerLink(
                    selfId = selfId,
                    remoteId = placeholder,
                    facade = host,
                    senderIdDeferred = senderId,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            // Before the ID-exchange resolves the roster carries the placeholder.
            assertEquals(setOf(selfId, placeholder), link.peers.value)

            senderId.complete(resolved)

            // Reconciliation swaps the placeholder for the peer's real id.
            val reconciled = link.peers.first { resolved in it }
            assertEquals(setOf(selfId, resolved), reconciled)

            // sendTo the real id now succeeds and delivers over the channel.
            link.sendTo(resolved, "hi".encodeToByteArray())
            assertContentEquals("hi".encodeToByteArray(), joiner.incomingBytes.first())
        }

    @Test
    fun sendToUnknownPeerThrowsPeerNotConnected() =
        runTest {
            val (hostFac, _) = PairedFacadeFactory.pair()
            val host = hostFac.create(IceConfig.NoServers, hostInitiated = true)

            val senderId = CompletableDeferred(PeerId("real-remote"))
            val link =
                WebRTCPeerLink(
                    selfId = PeerId("self"),
                    remoteId = PeerId("placeholder"),
                    facade = host,
                    senderIdDeferred = senderId,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            assertFailsWith<PeerNotConnected> {
                link.sendTo(PeerId("nobody"), byteArrayOf(1))
            }
        }

    @Test
    fun broadcastAfterRemoteCloseThrows() =
        runTest {
            val (hostFac, joinerFac) = PairedFacadeFactory.pair()
            val host = hostFac.create(IceConfig.NoServers, hostInitiated = true)
            val joiner = joinerFac.create(IceConfig.NoServers, hostInitiated = false)

            val link =
                WebRTCPeerLink(
                    selfId = PeerId("self"),
                    remoteId = PeerId("remote"),
                    facade = host,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            // Remote closes its data channel — drives the host link to Torn.
            joiner.close()
            link.state.first { it is SeamState.Torn }

            assertFailsWith<IllegalStateException> {
                link.broadcast("late".encodeToByteArray())
            }
            assertFailsWith<IllegalStateException> {
                link.sendTo(PeerId("remote"), "late".encodeToByteArray())
            }
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
