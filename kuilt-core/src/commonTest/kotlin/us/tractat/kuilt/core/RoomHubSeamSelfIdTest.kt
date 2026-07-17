@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [RoomHubSeam] must honour the [Seam.peers] contract (`Seam.kt` KDoc): the roster **includes
 * [Seam.selfId]** — the initial value is `{ selfId }`, and self stays present across roster
 * changes. The hub *is* a peer in its own room. Regression guard for #1506, where the hub's roster
 * modelled only remote spokes and silently diverged from every other `Seam` impl (and from the
 * #1490 continuous `selfId ∈ peers` monitor).
 */
class RoomHubSeamSelfIdTest {

    @Test
    fun peersAlwaysContainsSelfId() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val self = PeerId("server")
        val room = RoomHubSeam("table-7", self, RoomAuthorizer.AllowAll)
        val peer = PeerId("client-a")
        val sender = OutboundSender { /* no-op outbound */ }

        val initial = room.peers.value

        // Register a remote spoke via its first frame.
        room.deliver(peer, Swatch(payload = byteArrayOf(1), sender = peer), sender, principal = null)
        val afterJoin = room.peers.value

        // Remove the spoke.
        room.deregister(peer, sender)
        val afterLeave = room.peers.value

        assertAll(
            { assertEquals(setOf(self), initial, "initial value must be {selfId} per the Seam.peers contract") },
            { assertTrue(self in afterJoin, "selfId must remain in peers after a remote joins; got $afterJoin") },
            { assertTrue(peer in afterJoin, "the joined remote must appear in peers; got $afterJoin") },
            { assertTrue(self in afterLeave, "selfId must remain in peers after the remote leaves; got $afterLeave") },
        )
    }
}
