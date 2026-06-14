@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HandshakingTest {
    @Test
    fun learnsRemoteIdThenCarriesPayload() = runTest {
        val (a, b) = connPair()
        val seamA = async { handshaking(a, PeerId("A")) }
        val seamB = async { handshaking(b, PeerId("B")) }
        val sa = seamA.await()
        val sb = seamB.await()
        assertEquals(setOf(PeerId("A"), PeerId("B")), sa.peers.value)
        sa.broadcast(byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), sb.incoming.first().payload)
    }
}
