package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CompositePeersTest {
    @Test
    fun twoCompositePeersSeeEachOtherOnceAcrossSharedPlies() = runTest {
        // One shared InMemoryLoom mesh used as a single ply by both composite peers.
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem), dispatcher = UnconfinedTestDispatcher(testScheduler))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))

        // Each peer's `peers` eventually contains itself + the other composite id (size 2, no dup).
        val hostPeers = host.peers.first { it.size == 2 }
        assertEquals(2, hostPeers.size)
        assertEquals(true, host.selfId in hostPeers)
        assertEquals(true, joiner.selfId in hostPeers)
    }
}
