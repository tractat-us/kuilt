package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.MultipeerConnectivity.MCEncryptionRequired
import platform.MultipeerConnectivity.MCNearbyServiceAdvertiser
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionState
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.multipeer.internal.MCSessionLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression coverage for kuilt#1400: a host whose `MCSession` has latched
 * [SeamState.Torn] (its last peer dropped) keeps advertising until the next
 * weave/close reaps the advertiser. In that window an inbound invitation must be
 * **refused** — never auto-admitted into the dead session — regardless of the
 * advertiser lifecycle. The guard is local to [AcceptAllAdvertiserDelegate]:
 * refuse (`invitationHandler(false, null)`) whenever the host link is Torn.
 *
 * A live (Woven) host still accepts, admitting the joiner into the host session.
 *
 * The real `MCNearbyServiceAdvertiser` only fires its delegate from the
 * framework's private queue on live hardware, so these tests invoke the
 * delegate directly — the same seam the framework calls — driving the host
 * [MCSessionLink] to Woven/Torn through its own delegate first.
 *
 * This coverage also protects the jvm/`libkuilt.dylib` host path: its
 * `mc_runtime_open` opens the session through this same appleMain factory and
 * advertiser delegate, so the refusal guard applies there too.
 */
@OptIn(ExperimentalForeignApi::class)
class MultipeerAdvertiserInviteRefusalTest {
    @Test
    fun tornHostRefusesInboundInvitation() =
        runTest {
            val self = MCPeerID(displayName = "host")
            val session = newSession(self)
            val link = MCSessionLink(self, session)
            val delegate = MultipeerPeerLinkFactory.AcceptAllAdvertiserDelegate(link)

            // Bring the host to Woven, then drop the last peer so it latches Torn.
            val guest = MCPeerID(displayName = "guest")
            link.delegate.session(session, guest, MCSessionState.MCSessionStateConnected)
            link.delegate.session(session, guest, MCSessionState.MCSessionStateNotConnected)
            assertTrue(link.state.value is SeamState.Torn, "precondition: last-peer drop must latch Torn")

            var accepted: Boolean? = null
            var handedSession: MCSession? = null
            delegate.advertiser(newAdvertiser(self), MCPeerID(displayName = "latecomer"), null) { accept, admitted ->
                accepted = accept
                handedSession = admitted
            }

            assertEquals(false, accepted, "a Torn host must refuse the inbound invitation")
            assertNull(handedSession, "a refused invitation hands back no session")

            link.close()
        }

    @Test
    fun liveHostAcceptsInboundInvitation() =
        runTest {
            val self = MCPeerID(displayName = "host")
            val session = newSession(self)
            val link = MCSessionLink(self, session)
            val delegate = MultipeerPeerLinkFactory.AcceptAllAdvertiserDelegate(link)

            val guest = MCPeerID(displayName = "guest")
            link.delegate.session(session, guest, MCSessionState.MCSessionStateConnected)
            assertTrue(link.state.value is SeamState.Woven, "precondition: a connected peer makes the host Woven")

            var accepted: Boolean? = null
            var handedSession: MCSession? = null
            delegate.advertiser(newAdvertiser(self), MCPeerID(displayName = "joiner"), null) { accept, admitted ->
                accepted = accept
                handedSession = admitted
            }

            assertEquals(true, accepted, "a live (Woven) host still accepts the inbound invitation")
            assertSame(session, handedSession, "an accepted invitation is admitted into the host session")

            link.close()
        }

    private fun newSession(self: MCPeerID): MCSession =
        MCSession(
            peer = self,
            securityIdentity = null,
            encryptionPreference = MCEncryptionRequired,
        )

    private fun newAdvertiser(self: MCPeerID): MCNearbyServiceAdvertiser =
        MCNearbyServiceAdvertiser(
            peer = self,
            discoveryInfo = null,
            serviceType = "kuilt-t1400",
        )
}
