package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSessionState
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.multipeer.internal.MCSessionLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

/**
 * The Apple half of #1390's torn-send guard (#2444): a `Torn` [MCSessionLink] must reject
 * `broadcast` and `sendTo` with `IllegalStateException` rather than silently dropping the frame.
 *
 * ## Why this exists alongside [MultipeerAppleConformanceTest]
 *
 * The TCK's `sendOnTornSeamThrows` reaches this link now, and it is what pins `broadcast`. It
 * cannot pin `sendTo` on **this** fabric: [PeerNotConnected] *is* an `IllegalStateException`, and
 * the pre-fix `sendTo` threw exactly that once `close()` had emptied the session's
 * `connectedPeers` — so that arm of the obligation was satisfied by the defect. Two things here
 * close the hole the TCK leaves:
 *
 * 1. **The refusal is identified, not just counted.** [sendToOnAClosedLinkReportsTheTearNotAMissingPeer]
 *    asserts the throwable is *not* a [PeerNotConnected] — that the seam reports itself dead rather
 *    than blaming the addressee, which is the whole behavioural point of #2444.
 * 2. **The fake cannot be the one refusing.** The tear here is driven through the delegate's
 *    last-peer drop, which latches `Torn` **without** calling `session.disconnect()` — so
 *    [FakeMCSessionBus] never clears the endpoint's `connected` list. `session.connectedPeers` is
 *    still non-empty, [FakeMCSession.sendData] still routes unconditionally, and the remote link is
 *    still live and collecting. In that configuration the pre-fix code sends *successfully*: the
 *    only thing that can refuse is [MCSessionLink]'s own state check. Each test proves the rig by
 *    first broadcasting on the **live** link and asserting the frame arrives, so a green cannot
 *    come from a route that was never open.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class MCSessionLinkTornSendTest {

    @Test
    fun broadcastOnATornLinkThrowsEvenWhereTheSessionStillHasPeers() =
        runTest {
            val rig = TornSendRig(this)

            rig.link.broadcast(byteArrayOf(7))
            runCurrent()
            assertEquals(
                1,
                rig.received.size,
                "rig precondition: a live broadcast must reach the remote, or a later 'nothing arrived' proves nothing",
            )

            rig.tearWithoutDisconnecting()

            assertFailsWith<IllegalStateException>("broadcast on a Torn seam must throw") {
                rig.link.broadcast(byteArrayOf(8))
            }
            runCurrent()
            assertEquals(1, rig.received.size, "a Torn seam must not have delivered the rejected frame")
        }

    @Test
    fun sendToOnATornLinkThrowsEvenWhereTheAddressedPeerIsStillConnected() =
        runTest {
            val rig = TornSendRig(this)
            val remoteId = rig.remoteLink.selfId

            rig.link.sendTo(remoteId, byteArrayOf(7))
            runCurrent()
            assertEquals(
                1,
                rig.received.size,
                "rig precondition: a live sendTo must reach the remote, or a later 'nothing arrived' proves nothing",
            )

            rig.tearWithoutDisconnecting()

            assertFailsWith<IllegalStateException>("sendTo on a Torn seam must throw") {
                rig.link.sendTo(remoteId, byteArrayOf(8))
            }
            runCurrent()
            assertEquals(1, rig.received.size, "a Torn seam must not have delivered the rejected frame")
        }

    /**
     * The shape #2444 names: after `close()` the session's `connectedPeers` really is empty, so the
     * pre-fix `sendTo` failed the lookup and threw [PeerNotConnected] — an `IllegalStateException`,
     * which is why the TCK's assertion could not see the defect. The guard must run *ahead* of the
     * lookup so the seam reports its own death rather than a missing peer.
     */
    @Test
    fun sendToOnAClosedLinkReportsTheTearNotAMissingPeer() =
        runTest {
            val rig = TornSendRig(this)
            val remoteId = rig.remoteLink.selfId

            rig.link.close()
            assertIs<SeamState.Torn>(rig.link.state.value, "precondition: close() must latch Torn")

            val failure = assertFailsWith<IllegalStateException> { rig.link.sendTo(remoteId, byteArrayOf(9)) }
            assertIsNot<PeerNotConnected>(
                failure,
                "a torn seam must report the tear, not blame the addressee: ${failure.message}",
            )
        }
}

/**
 * One [FakeMCSessionBus] with two live [MCSessionLink]s, plus a collector on the remote's
 * `incoming`, so a send from [link] is observable end-to-end.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
private class TornSendRig(scope: TestScope) {

    private val bus = FakeMCSessionBus()

    private val selfPeer = MCPeerID(displayName = "torn-send-self#aaaaaaaa")
    private val remotePeer = MCPeerID(displayName = "torn-send-remote#bbbbbbbb")

    private val session = bus.session(selfPeer)
    private val remoteSession = bus.session(remotePeer)

    val link = MCSessionLink(selfPeer, session, dispatcher = UnconfinedTestDispatcher(scope.testScheduler))
    val remoteLink =
        MCSessionLink(remotePeer, remoteSession, dispatcher = UnconfinedTestDispatcher(scope.testScheduler))

    /** Frames the remote actually received. */
    val received: MutableList<Swatch> = mutableListOf()

    init {
        session.delegate = link.delegate
        remoteSession.delegate = remoteLink.delegate
        bus.connect()
        assertIs<SeamState.Woven>(link.state.value, "rig precondition: the local link must be Woven")
        assertIs<SeamState.Woven>(remoteLink.state.value, "rig precondition: the remote link must be Woven")

        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            remoteLink.incoming.collect { received += it }
        }
    }

    /**
     * Latch `Torn` on [link] through the delegate's last-peer drop — the path a radio failure takes.
     * It issues no `session.disconnect()`, so the bus leaves the endpoint's `connected` list intact:
     * `connectedPeers` still names [remotePeer], `sendData` still routes, and [remoteLink] is still
     * live. Anything that refuses a send after this is [MCSessionLink]'s own guard.
     */
    fun tearWithoutDisconnecting() {
        link.delegate.session(session, remotePeer, MCSessionState.MCSessionStateNotConnected)
        assertIs<SeamState.Torn>(link.state.value, "rig precondition: the last-peer drop must latch Torn")
        assertTrue(
            session.connected.isNotEmpty(),
            "rig precondition: the fake must still report a connected peer, or the fake is the one refusing",
        )
        assertIs<SeamState.Woven>(remoteLink.state.value, "rig precondition: the remote must still be live")
    }
}
