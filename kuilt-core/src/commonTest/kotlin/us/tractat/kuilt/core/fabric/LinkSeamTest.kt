@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
}
