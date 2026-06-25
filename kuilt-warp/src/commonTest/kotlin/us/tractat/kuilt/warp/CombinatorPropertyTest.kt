package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LatticeProduct
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Property tests for the B3 monotone combinator extensions:
 * - [zip] — pairs two coordination-free values into a [LatticeProduct]
 * - [joinAllOrNull] — empty-safe merge that returns null on empty input
 *
 * Witness for zip: [GCounter] × [PNCounter] — two non-generic CRDTs with
 * different join operations, avoiding type-capture issues with nested generics.
 */
class CombinatorPropertyTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")
    private val r3 = ReplicaId("r3")

    private fun counterCf(replica: ReplicaId, count: Long): CoordinationFree<GCounter> =
        CoordinationFree(GCounter.of(replica to count))

    private fun pnCf(replica: ReplicaId, inc: Long): CoordinationFree<PNCounter> =
        CoordinationFree(PNCounter.ZERO.piece(PNCounter.ZERO.increment(replica, inc).delta))

    // ── zip ──────────────────────────────────────────────────────────────────

    @Test
    fun `zip produces a product of both states`() {
        val zipped: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r1, 5L).zip(pnCf(r1, 10L))

        assertEquals(5L, zipped.state.first.value)
        assertEquals(10L, zipped.state.second.value)
    }

    @Test
    fun `zip result is idempotent under embroider`() {
        val zipped: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r1, 3L).zip(pnCf(r1, 1L))

        assertEquals(zipped.state, zipped.embroider(zipped).state)
    }

    @Test
    fun `zip result is commutative under embroider`() {
        val zA: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r1, 2L).zip(pnCf(r1, 5L))
        val zB: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r2, 8L).zip(pnCf(r2, 3L))

        assertEquals(zA.embroider(zB).state, zB.embroider(zA).state)
    }

    @Test
    fun `zip result is associative under embroider`() {
        val zA: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r1, 1L).zip(pnCf(r1, 1L))
        val zB: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r2, 2L).zip(pnCf(r2, 2L))
        val zC: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r3, 3L).zip(pnCf(r3, 3L))

        val leftAssoc = zA.embroider(zB).embroider(zC)
        val rightAssoc = zA.embroider(zB.embroider(zC))

        assertEquals(leftAssoc.state, rightAssoc.state)
    }

    @Test
    fun `zip both components converge independently`() {
        val zA: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r1, 10L).zip(pnCf(r1, 100L))
        val zB: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
            counterCf(r2, 20L).zip(pnCf(r2, 200L))

        val merged = zA.embroider(zB)
        assertEquals(30L, merged.state.first.value)    // GCounter: 10 + 20
        assertEquals(300L, merged.state.second.value)  // PNCounter: 100 + 200
    }

    // ── joinAllOrNull ────────────────────────────────────────────────────────

    @Test
    fun `joinAllOrNull returns null on empty list`() {
        val result = joinAllOrNull(emptyList<CoordinationFree<GCounter>>())
        assertNull(result)
    }

    @Test
    fun `joinAllOrNull with one element is identity`() {
        val single = CoordinationFree(GCounter.of(r1 to 7L))
        val result = joinAllOrNull(listOf(single))
        assertEquals(single.state, result?.state)
    }

    @Test
    fun `joinAllOrNull with multiple elements equals joinAll`() {
        val a = CoordinationFree(GCounter.of(r1 to 10L))
        val b = CoordinationFree(GCounter.of(r2 to 20L))
        val contributions = listOf(a, b)

        assertEquals(joinAll(contributions).state, joinAllOrNull(contributions)?.state)
    }

    @Test
    fun `joinAllOrNull result is the upper bound of all inputs`() {
        val a = CoordinationFree(GCounter.of(r1 to 10L))
        val b = CoordinationFree(GCounter.of(r2 to 20L))
        val c = CoordinationFree(GCounter.of(r3 to 30L))

        val result = joinAllOrNull(listOf(a, b, c))
        assertEquals(60L, result?.state?.value)
    }
}
