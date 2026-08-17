package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.MultipeerConnectivity.MCEncryptionRequired
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionState
import us.tractat.kuilt.multipeer.internal.MCSessionLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Apple half of #1851 — the #1816 obligation that a `Torn` seam's `peers` is exactly
 * `{ selfId }`, and that the collapse is published *before* the terminal `Torn` latch.
 *
 * `MultipeerConformanceTest` runs the TCK's `peersCollapseToSelfIdWhenTorn` against
 * `BridgePeerLink` on the JVM. It cannot reach [MCSessionLink], which is `appleMain` — so
 * without this file the Apple link's identical fix would rest on inspection alone. As in
 * [MultipeerPeerLinkFactoryTerminalDropTest], a real `MCSession` cannot be driven from a unit
 * test (MC only fires its delegate from the framework's private queue on live hardware), so
 * these drive [MCSessionLink.delegate] directly — the same seam the framework calls.
 */
@OptIn(ExperimentalForeignApi::class)
class MCSessionLinkTearCollapseTest {
    @Test
    fun localCloseCollapsesPeersToSelfId() =
        runTest {
            val self = MCPeerID(displayName = "self")
            val session = newSession(self)
            val link = MCSessionLink(self, session)

            val guest = MCPeerID(displayName = "guest")
            link.delegate.session(session, guest, MCSessionState.MCSessionStateConnected)
            assertTrue(
                link.peers.value.size >= 2,
                "precondition: the guest must be in the roster before the tear (got ${link.peers.value})",
            )

            link.close()

            // A torn fabric can reach nobody. Before #1851 tearDown never touched the roster, so a
            // locally-closed link went on advertising the guest forever.
            assertEquals(setOf(link.selfId), link.peers.value, "a Torn seam must advertise no reachable remote peer")
        }

    @Test
    fun aPostDisconnectCallbackCannotRepublishPeersOntoATornSeam() =
        runTest {
            val self = MCPeerID(displayName = "self")
            val session = newSession(self)
            val link = MCSessionLink(self, session)

            val a = MCPeerID(displayName = "a")
            val b = MCPeerID(displayName = "b")
            link.delegate.session(session, a, MCSessionState.MCSessionStateConnected)
            link.delegate.session(session, b, MCSessionState.MCSessionStateConnected)
            assertTrue(
                link.peers.value.size >= 3,
                "precondition: both guests must be in the roster before the tear (got ${link.peers.value})",
            )

            link.close()
            assertEquals(setOf(link.selfId), link.peers.value, "close() must collapse the roster")

            // close() issued session.disconnect(), so MC now reports .notConnected for every peer
            // that was connected — arriving AFTER the seam is already Torn. The delegate recomputes
            // the roster as `registry.peers + selfId`, so a tear that left the bindings behind would
            // let this first callback republish `b` onto a Torn seam, undoing the collapse. That the
            // roster only re-converges once EVERY remaining callback arrives is precisely the
            // guarantee #1851 says MC does not give.
            link.delegate.session(session, a, MCSessionState.MCSessionStateNotConnected)

            assertEquals(
                setOf(link.selfId),
                link.peers.value,
                "a post-teardown disconnect resurrected a peer onto an already-Torn seam",
            )
        }

    private fun newSession(self: MCPeerID): MCSession =
        MCSession(
            peer = self,
            securityIdentity = null,
            encryptionPreference = MCEncryptionRequired,
        )
}
