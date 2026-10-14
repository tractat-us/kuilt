package us.tractat.kuilt.multipeer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MultipeerDiscoveryUpdatesTest {
    @Test
    fun replayReservedBeforeTeardownIsRejectedAfterAcquiringTheNextGeneration() = runTest {
        val updates = MultipeerDiscoveryUpdates()
        val old = MultipeerAdvertisement("old", "Old", "kuilt-test")
        val current = MultipeerAdvertisement("current", "Current", "kuilt-test")
        updates.publish(setOf(old), old)
        val subscribed = CompletableDeferred<Unit>()
        val acquire = CompletableDeferred<Unit>()
        lateinit var generation: MultipeerBrowseGeneration
        val seen = mutableListOf<String>()
        val collector = backgroundScope.launch {
            updates.events.onSubscription {
                subscribed.complete(Unit)
                acquire.await()
                generation = updates.generation
            }.advertisements { generation }.collect { seen += it.peerKey }
        }
        runCurrent()
        assertEquals(true, subscribed.isCompleted, "the old replay must be reserved before teardown")
        updates.endSession()
        updates.publish(setOf(current), current)
        acquire.complete(Unit)
        runCurrent()
        assertEquals(listOf("current"), seen, "only the acquired generation may seed the collector")
        collector.cancelAndJoin()
    }

    @Test
    fun liveUpdateDuringSnapshotDeliveryCannotOvertakeTheSnapshot() = runTest {
        val updates = MultipeerDiscoveryUpdates()
        val first = MultipeerAdvertisement("first", "First", "kuilt-test")
        val old = MultipeerAdvertisement("second", "Old", "kuilt-test")
        val fresh = old.copy(sessionName = "Fresh")
        updates.publish(setOf(first, old), old)
        val seen = mutableListOf<String>()
        val collector = backgroundScope.launch {
            updates.events.advertisements { updates.generation }.collect { tag ->
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
        val flow = updates.events.advertisements { updates.generation }
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
