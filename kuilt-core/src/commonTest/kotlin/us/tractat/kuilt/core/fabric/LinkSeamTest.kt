@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinkSeamTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")

    @Test
    fun wovenAtConstructionAndDeliversFromRemote() = runTest {
        val (mine, theirs) = connectionPair()
        val seam = identified(mine, self, remote, UnconfinedTestDispatcher(testScheduler))
        assertIs<SeamState.Woven>(seam.state.value)
        assertEquals(setOf(self, remote), seam.peers.value)
        theirs.send(byteArrayOf(9))
        val swatch = seam.incoming.first()
        assertContentEquals(byteArrayOf(9), swatch.toByteArray())
        assertEquals(remote, swatch.sender)
    }

    @Test
    fun concurrentBroadcastsArriveInSendOrder() = runTest {
        val (mine, theirs) = connectionPair()
        val seam = identified(mine, self, remote, UnconfinedTestDispatcher(testScheduler))
        repeat(3) { seam.broadcast(byteArrayOf(it.toByte())) }
        val got = theirs.incoming.take(3).toList().map { it.single() }
        assertEquals(listOf<Byte>(0, 1, 2), got)
    }

    /**
     * Inbox is bounded: a Strict policy (capacity=1, FAIL overflow) tears the seam when the buffer
     * overflows. Under an UNLIMITED inbox this could never happen — the seam would just keep
     * buffering. This test proves the inbox has a real bound.
     */
    @Test
    fun inboxOverflowTearsSeamWhenPolicyIsStrict() = runTest {
        val (mine, theirs) = connectionPair()
        val strictPolicy = DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL)
        val seam = identified(mine, self, remote, UnconfinedTestDispatcher(testScheduler), strictPolicy)
        // Fill the inbox (capacity 1) without draining incoming.
        theirs.send(byteArrayOf(1))
        // Second frame overflows the spool — readLoop catches FrameOverflow as a generic Exception
        // and falls through to tearDown, so the seam transitions to Torn.
        theirs.send(byteArrayOf(2))
        // Seam tears down due to overflow — eventually Torn.
        seam.state.first { it is SeamState.Torn }
        assertIs<SeamState.Torn>(seam.state.value)
    }
}
