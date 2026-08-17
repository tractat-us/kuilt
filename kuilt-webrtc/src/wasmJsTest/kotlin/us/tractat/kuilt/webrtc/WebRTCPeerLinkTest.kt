package us.tractat.kuilt.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacade
import us.tractat.kuilt.webrtc.internal.WebRTCPeerLink
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * #2427, local-then-remote: the reason the **application** supplied must survive the
     * data-channel close that its own [WebRTCPeerLink.close] causes.
     *
     * `BrowserRtcFacade.close()` calls `dataChannel.close()`, whose `onclose` completes the
     * deferred that the `init` block's tear coroutine is parked on — so the remote tear path fires
     * *while* `WebRTCPeerLink.close` is still inside `facade.close()`, before its
     * `finally { scope.cancel() }` can shut the window. With no single-shot guard on `tear`, that
     * second tear overwrote `Torn(Normal)` with `Torn(RemoteRequested)`, and a consumer branching
     * on [CloseReason] to decide whether to reconnect read the wrong answer.
     */
    @Test
    fun localCloseReasonSurvivesTheDataChannelCloseItCauses() =
        runTest {
            val (hostFac, _) = PairedFacadeFactory.pair()
            val host = ClosingYieldsFacade(hostFac.create(IceConfig.NoServers, hostInitiated = true))
            val link =
                WebRTCPeerLink(
                    selfId = PeerId("self"),
                    remoteId = PeerId("remote"),
                    facade = host,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            link.close(CloseReason.Normal)

            assertEquals(SeamState.Torn(CloseReason.Normal), link.state.value)
        }

    /**
     * #2427, remote-then-local: the *first* tear wins. [SeamState.Torn] is documented terminal
     * (`SeamState.kt`), so a routine `close()` issued after the peer has already vanished must not
     * relabel the seam `Normal` — the peer really did go away, and that is the reading a consumer
     * reconnects on.
     *
     * Needs no timing at all: `close()` consults only its own `closed` flag, which the remote tear
     * path never sets.
     */
    @Test
    fun remoteTearReasonSurvivesASubsequentLocalClose() =
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

            joiner.close()
            link.state.first { it is SeamState.Torn }
            assertEquals(SeamState.Torn(CloseReason.RemoteRequested), link.state.value)

            link.close(CloseReason.Normal)

            assertEquals(SeamState.Torn(CloseReason.RemoteRequested), link.state.value)
        }

    /**
     * What #2427's guard is now unpinned on: `tear` no-ops on an already-Torn seam, so nothing in
     * `close`'s own body reports whether it ran. The tempting "simplification" — early-returning
     * from [WebRTCPeerLink.close] on `state is Torn`, since the tear is a no-op anyway — would leak
     * the facade and the seam's scope on every remotely-torn link, and no other test would notice.
     *
     * Asserted on outcomes rather than on the call: the scope is cancelled, and the host facade's
     * outbound spool is closed, which is observable only as the *joiner's* incoming flow completing.
     * The joiner's own `close()` cannot have done it — it closes the other direction.
     */
    @Test
    fun closeAfterARemoteTearStillTearsDownTheFacadeAndScope() =
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

            joiner.close()
            link.state.first { it is SeamState.Torn }
            assertTrue(link.scope.isActive, "scope is still live after the remote tear")

            link.close(CloseReason.Normal)

            assertFalse(link.scope.isActive, "close must cancel the seam scope")
            // Completes only because the host facade closed its outbound spool.
            assertEquals(emptyList(), joiner.incomingBytes.toList())
        }
}

/**
 * The paired fake's facade, with a [close] that **yields** after signalling the channel close.
 *
 * The yield is the load-bearing part of #2427's local-close reproduction: it hands control to the
 * parked `awaitDataChannelClose()` coroutine while `WebRTCPeerLink.close` is still inside
 * `facade.close()`. Without it the reproduction rests on whether the injected dispatcher happens to
 * resume that continuation inline, which is a property of the test dispatcher rather than of the
 * code under test.
 *
 * Models `BrowserRtcFacade.close()`, whose `dataChannel.close()` fires the `onclose` that completes
 * the same deferred — [RtcPeerConnectionFacade.close] is declared `suspend`, so an implementation
 * is free to suspend there.
 */
private class ClosingYieldsFacade(
    private val delegate: RtcPeerConnectionFacade,
) : RtcPeerConnectionFacade by delegate {
    override suspend fun close() {
        delegate.close()
        yield()
    }
}
