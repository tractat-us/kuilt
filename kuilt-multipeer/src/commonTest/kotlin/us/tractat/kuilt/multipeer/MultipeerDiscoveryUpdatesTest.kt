package us.tractat.kuilt.multipeer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MultipeerDiscoveryUpdatesTest {
    @Test
    fun liveUpdateDuringSnapshotDeliveryCannotOvertakeTheSnapshot() = runTest {
        val updates = MultipeerDiscoveryUpdates()
        val first = MultipeerAdvertisement("first", "First", "kuilt-test")
        val old = MultipeerAdvertisement("second", "Old", "kuilt-test")
        val fresh = old.copy(sessionName = "Fresh")
        updates.publish(setOf(first, old), old)
        val seen = mutableListOf<String>()
        val collector = backgroundScope.launch {
            updates.events.advertisements().collect { tag ->
                seen += tag.sessionName
                if (seen.size == 1) {
                    // Deliver a real update while the initial snapshot is still being emitted.
                    updates.publish(setOf(first, fresh), fresh)
                }
            }
        }
        runCurrent()
        assertEquals(listOf("First", "Old", "Fresh"), seen, "the live update must follow, never precede, the snapshot")
        collector.cancelAndJoin()
    }

    @Test
    fun eachCollectorSeedsFromTheLatestSnapshotIncludingLossAndTeardown() = runTest {
        val updates = MultipeerDiscoveryUpdates()
        val peer = MultipeerAdvertisement("peer", "Peer", "kuilt-test")
        updates.publish(setOf(peer), peer)
        val flow = updates.events.advertisements()
        val seen = mutableListOf<String>()
        val first = backgroundScope.launch { flow.collect { seen += it.peerKey } }
        runCurrent()
        first.cancelAndJoin()
        assertEquals(listOf("peer"), seen)
        seen.clear()
        val second = backgroundScope.launch { flow.collect { seen += it.peerKey } }
        runCurrent()
        second.cancelAndJoin()
        assertEquals(listOf("peer"), seen, "seed state belongs to each collection")
        updates.publish(emptySet())
        seen.clear()
        val afterClear = backgroundScope.launch { flow.collect { seen += it.peerKey } }
        runCurrent()
        assertEquals(emptyList(), seen, "clearing peers must replace the replayed snapshot")
        afterClear.cancelAndJoin()
    }
}
