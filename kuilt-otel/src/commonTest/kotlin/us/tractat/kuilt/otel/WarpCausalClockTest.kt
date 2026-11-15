package us.tractat.kuilt.otel

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

class WarpCausalClockTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    @Test
    fun tickMintsMonotonicDotsWithFrontierPredecessors() = runTest {
        val clock = WarpCausalClock(replicaA).armed()
        val first = clock.mint()
        val second = clock.mint()
        val third = clock.mint()
        assertAll(
            { assertEquals(emptySet(), first.predecessors, "first tick has empty frontier") },
            { assertEquals(1L, first.dot.seq) },
            { assertEquals(2L, second.dot.seq) },
            { assertEquals(3L, third.dot.seq) },
            { assertEquals(setOf(first.dot), second.predecessors) },
            { assertEquals(setOf(second.dot), third.predecessors) },
            { assertEquals(replicaA, first.dot.replica) },
        )
    }

    @Test
    fun frontierReflectsMostRecentTick() = runTest {
        val clock = WarpCausalClock(replicaA).armed()
        assertEquals(emptySet(), clock.frontier())
        val stamp = clock.mint()
        assertEquals(setOf(stamp.dot), clock.frontier())
    }

    // THE load-bearing test: a restart must never reissue a used seq.
    @Test
    fun recoverDoesNotReissueUsedSeq() = runTest(StandardTestDispatcher()) {
        val store = InMemoryDurableStore()
        val clock1 = WarpCausalClock(replicaA).armed()
        clock1.mint()
        val second = clock1.mint()
        clock1.persist(store)

        // Fresh clock — simulates a process restart that would otherwise reset seq to 0.
        val clock2 = WarpCausalClock(replicaA)
        clock2.recover(store)
        val third = clock2.mint()

        assertAll(
            { assertEquals(3L, third.dot.seq, "next tick must continue past the persisted seq") },
            { assertTrue(third.dot.seq > second.dot.seq, "no reissue of a used seq") },
            { assertEquals(setOf(second.dot), third.predecessors, "frontier survives recovery") },
        )
    }

    @Test
    fun recoverOnEmptyStoreStartsFresh() = runTest(StandardTestDispatcher()) {
        val clock = WarpCausalClock(replicaA)
        clock.recover(InMemoryDurableStore())
        val first = clock.mint()
        assertEquals(1L, first.dot.seq)
    }

    @Test
    fun observeFoldsRemoteFrontierIntoNextTick() = runTest {
        val clock = WarpCausalClock(replicaA).armed()
        val local = clock.mint()
        val remote = Dot(replicaB, 7L)
        clock.observe(setOf(remote))
        val next = clock.mint()
        assertEquals(setOf(local.dot, remote), next.predecessors)
    }
}
