package us.tractat.kuilt.otel

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertAll

class WarpCausalClockTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    @Test
    fun tickMintsMonotonicDotsWithFrontierPredecessors() {
        val clock = WarpCausalClock(replicaA)
        val first = clock.tick()
        val second = clock.tick()
        val third = clock.tick()
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
    fun frontierReflectsMostRecentTick() {
        val clock = WarpCausalClock(replicaA)
        assertEquals(emptySet(), clock.frontier())
        val stamp = clock.tick()
        assertEquals(setOf(stamp.dot), clock.frontier())
    }

    // THE load-bearing test: a restart must never reissue a used seq.
    @Test
    fun recoverDoesNotReissueUsedSeq() = runTest(StandardTestDispatcher()) {
        val store = InMemoryDurableStore()
        val clock1 = WarpCausalClock(replicaA)
        clock1.tick()
        val second = clock1.tick()
        clock1.persist(store)

        // Fresh clock — simulates a process restart that would otherwise reset seq to 0.
        val clock2 = WarpCausalClock(replicaA)
        clock2.recover(store)
        val third = clock2.tick()

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
        val first = clock.tick()
        assertEquals(1L, first.dot.seq)
    }

    @Test
    fun observeFoldsRemoteFrontierIntoNextTick() {
        val clock = WarpCausalClock(replicaA)
        val local = clock.tick()
        val remote = Dot(replicaB, 7L)
        clock.observe(setOf(remote))
        val next = clock.tick()
        assertEquals(setOf(local.dot, remote), next.predecessors)
    }
}
