package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.MultipeerConnectivity.MCPeerID
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.multipeer.internal.MCSessionLink
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Apple half of #1494's self-connection guard (#2445): an [MCSessionLink] handed a connection
 * whose remote identity is its own `selfId` must neither register it nor send to it.
 *
 * ## Why two tests rather than one
 *
 * On the JVM half of this fabric one line closes both shapes — `BridgePeerLink` owns `_peers` and
 * derives its send targets from it, so refusing the bind refuses the target too. On Apple the two
 * are **decoupled**: the delegate maintains the registry while `broadcast`/`sendTo` read
 * `session.connectedPeers` straight from the framework. So there are two independent defects, and
 * each needs the arm that can see it:
 *
 * 1. [aSelfDialNeverWeavesTheSeamNorJoinsTheRoster] pins the **delegate guard**. The roster cannot
 *    see it — the delegate republishes `registry.peers + selfId`, which re-adds `selfId` whether or
 *    not self was bound, so `peers` is byte-identical either way. `state` is what tells them apart:
 *    a link that has met nobody must stay [SeamState.Weaving], and binding a self-peer flips it to
 *    [SeamState.Woven] — a lone device reporting a woven session with itself.
 * 2. [aSelfDialledLinkDoesNotBroadcastToItself] pins the **send-path filter**, which is the
 *    live-self-loopback shape [us.tractat.kuilt.conformance.SeamConformanceSuite.selfDialIsRejected]
 *    asserts. Note the delegate guard alone does *not* close it: `connectedPeers` names the
 *    self-sighting regardless of what the registry decided.
 *
 * ## What this proves, and what it does not
 *
 * [FakeMCSessionBus] is a Kotlin/Native subclass of `MCSession`, so this proves the **link's**
 * reaction to a self-dial the framework presents. It cannot prove that real MultipeerConnectivity
 * ever *presents* one — that needs two devices on a radio, and no unit test on any platform can
 * reach it. The filter is a no-op if MC excludes a same-named peer from `connectedPeers` and
 * load-bearing if it does not.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class MCSessionLinkSelfDialTest {

    @Test
    fun aSelfDialNeverWeavesTheSeamNorJoinsTheRoster() =
        runTest {
            val rig = SelfDialRig(this, withRemote = false)
            assertIs<SeamState.Weaving>(
                rig.link.state.value,
                "rig precondition: a link that has met nobody starts Weaving",
            )

            assertTrue(rig.injectSelfDial(), "rig precondition: the bus must actually offer a self-dial")

            assertAll(
                {
                    assertIs<SeamState.Weaving>(
                        rig.link.state.value,
                        "a dropped self-dial is not a peer: a link that has met only itself must stay Weaving",
                    )
                },
                {
                    assertEquals(
                        setOf(rig.link.selfId),
                        rig.link.peers.value,
                        "a dropped self-dial must leave the roster at {selfId}",
                    )
                },
            )
        }

    @Test
    fun aSelfDialledLinkDoesNotBroadcastToItself() =
        runTest {
            val rig = SelfDialRig(this, withRemote = true)

            rig.link.broadcast(byteArrayOf(7))
            runCurrent()
            assertEquals(
                1,
                rig.remoteReceived.size,
                "rig precondition: a live broadcast must reach the remote, or a later 'nothing echoed' proves nothing",
            )

            assertTrue(rig.injectSelfDial(), "rig precondition: the bus must actually offer a self-dial")
            assertTrue(
                rig.selfIsOfferedAsAConnectedPeer,
                "rig precondition: the session must report the self-sighting among its connectedPeers — " +
                    "otherwise the send path was never asked to filter anything",
            )

            rig.link.broadcast(byteArrayOf(0x5E, 0x1F))
            runCurrent()

            assertAll(
                {
                    assertEquals(
                        emptyList(),
                        rig.selfReceived,
                        "a rejected self-dial must not be a send target: the link's own broadcast must never " +
                            "loop back to it attributed to selfId",
                    )
                },
                {
                    assertEquals(
                        2,
                        rig.remoteReceived.size,
                        "filtering self out of the send targets must not stop the broadcast reaching real peers",
                    )
                },
            )
        }
}

/**
 * One [FakeMCSessionBus] carrying the link under test, optionally with a live remote, plus a
 * collector on each end's `incoming` so a self-echo and a real delivery are separately observable.
 *
 * @param withRemote `false` builds a lone, never-connected link — the only configuration in which
 *   the delegate guard is observable at all, since `state` can then still move.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
private class SelfDialRig(scope: TestScope, withRemote: Boolean) {

    private val bus = FakeMCSessionBus()

    private val selfPeer = MCPeerID(displayName = "self-dial-local#aaaaaaaa")
    private val remotePeer = MCPeerID(displayName = "self-dial-remote#bbbbbbbb")

    private val session = bus.session(selfPeer)

    val link = MCSessionLink(selfPeer, session, dispatcher = UnconfinedTestDispatcher(scope.testScheduler))

    /** Frames the link under test received — a non-empty list after its own broadcast is the defect. */
    val selfReceived: MutableList<Swatch> = mutableListOf()

    /** Frames the remote received, so "nothing echoed" cannot be green because nothing was sent. */
    val remoteReceived: MutableList<Swatch> = mutableListOf()

    /** Does the fake session actually name a peer sharing our display name? The rig's own firing check. */
    val selfIsOfferedAsAConnectedPeer: Boolean
        get() = session.connected.any { it.displayName == selfPeer.displayName }

    init {
        session.delegate = link.delegate
        if (withRemote) {
            val remoteSession = bus.session(remotePeer)
            val remoteLink =
                MCSessionLink(remotePeer, remoteSession, dispatcher = UnconfinedTestDispatcher(scope.testScheduler))
            remoteSession.delegate = remoteLink.delegate
            bus.connect()
            assertIs<SeamState.Woven>(link.state.value, "rig precondition: the local link must be Woven")
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                remoteLink.incoming.collect { remoteReceived += it }
            }
        }
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            link.incoming.collect { selfReceived += it }
        }
    }

    fun injectSelfDial(): Boolean = bus.injectSelfDial(selfPeer)
}
