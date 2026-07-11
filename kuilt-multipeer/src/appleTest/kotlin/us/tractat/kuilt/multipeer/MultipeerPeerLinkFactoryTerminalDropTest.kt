package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import platform.MultipeerConnectivity.MCEncryptionRequired
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionState
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.multipeer.internal.MCSessionLink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Regression coverage for kuilt#1372: a self-disconnected `MCSession` must free
 * the factory's single-session slot so a reconnect works without an explicit
 * [MultipeerPeerLinkFactory.close].
 *
 * The real `MCSession` can't be driven from a unit test (MC only fires its
 * delegate from the framework's private queue on live hardware), so these tests
 * invoke the [MCSessionLink.delegate] directly — the same seam the framework
 * calls — with synthetic peer-state transitions.
 */
@OptIn(ExperimentalForeignApi::class)
class MultipeerPeerLinkFactoryTerminalDropTest {
    @Test
    fun factoryIsReusableAfterTerminalPeerDrop() =
        runTest {
            val factory = MultipeerPeerLinkFactory(displayName = "host", serviceType = "kuilt-t1372")
            val first = factory.weave(Rendezvous.New(Pattern("room"))) as MCSessionLink
            val guest = MCPeerID(displayName = "guest")

            // Successful connect, then the remote peer terminally drops.
            first.delegate.session(first.session, guest, MCSessionState.MCSessionStateConnected)
            first.delegate.session(first.session, guest, MCSessionState.MCSessionStateNotConnected)

            // Before the fix this throws "already has an active session".
            val second = factory.weave(Rendezvous.New(Pattern("room"))) as MCSessionLink
            assertNotSame(first, second)
            factory.close()
        }

    @Test
    fun terminalDropLatchesTornAndCompletesIncoming() =
        runTest {
            val self = MCPeerID(displayName = "self")
            val session = newSession(self)
            val link = MCSessionLink(self, session)

            // The Seam contract: `incoming` completes once the seam reaches Torn,
            // whether via local close OR a remote disconnect. Collect it — the
            // collector must terminate when the terminal drop tears the seam.
            val collector = launch { link.incoming.collect { } }

            val guest = MCPeerID(displayName = "guest")
            link.delegate.session(session, guest, MCSessionState.MCSessionStateConnected)
            link.delegate.session(session, guest, MCSessionState.MCSessionStateNotConnected)

            // Completes because the terminal drop closed the spool.
            collector.join()

            assertTrue(link.state.value is SeamState.Torn, "terminal drop must latch Torn")
            link.close()
        }

    @Test
    fun terminalDropInvokesOnTerminatedOnlyWhenLastPeerGone() =
        runTest {
            val self = MCPeerID(displayName = "self")
            val session = newSession(self)
            val link = MCSessionLink(self, session)
            var terminated = 0
            link.onTerminated = { terminated++ }

            val a = MCPeerID(displayName = "a")
            val b = MCPeerID(displayName = "b")

            // Mid-establishment churn must never free the slot.
            link.delegate.session(session, a, MCSessionState.MCSessionStateConnecting)
            assertFalse(terminated > 0, "Connecting must not fire onTerminated")

            link.delegate.session(session, a, MCSessionState.MCSessionStateConnected)
            link.delegate.session(session, b, MCSessionState.MCSessionStateConnected)

            // First of two peers drops — session still has a live peer, no free.
            link.delegate.session(session, a, MCSessionState.MCSessionStateNotConnected)
            assertFalse(terminated > 0, "a partial drop must not fire onTerminated")

            // Last peer drops — whole session is dead, free the slot.
            link.delegate.session(session, b, MCSessionState.MCSessionStateNotConnected)
            assertTrue(terminated == 1, "the last peer dropping must fire onTerminated exactly once")

            link.close()
        }

    @Test
    fun failedInviteThatNeverConnectsFreesTheSlot() =
        runTest {
            val self = MCPeerID(displayName = "self")
            val session = newSession(self)
            val link = MCSessionLink(self, session)
            var terminated = 0
            link.onTerminated = { terminated++ }

            val target = MCPeerID(displayName = "target")
            // Invite times out: Connecting then NotConnected, never Connected.
            link.delegate.session(session, target, MCSessionState.MCSessionStateConnecting)
            link.delegate.session(session, target, MCSessionState.MCSessionStateNotConnected)
            assertTrue(terminated == 1, "a never-connected invite failure must free the slot")

            link.close()
        }

    private fun newSession(self: MCPeerID): MCSession =
        MCSession(
            peer = self,
            securityIdentity = null,
            encryptionPreference = MCEncryptionRequired,
        )
}
