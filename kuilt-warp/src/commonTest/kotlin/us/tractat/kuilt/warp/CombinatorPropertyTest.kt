package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LatticeProduct
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Property tests for the B3 monotone combinator extensions:
 * - [zip] — pairs two coordination-free values into a [LatticeProduct]
 * - [joinAllOrNull] — empty-safe merge that returns null on empty input
 */
class CombinatorPropertyTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")

    // ── zip ──────────────────────────────────────────────────────────────────

    @Test
    fun `zip produces a product of both states`() {
        val counter = CoordinationFree(GCounter.of(r1 to 5L))
        val set = CoordinationFree(GSet.of("alpha", "beta"))

        val zipped = counter.zip(set)

        assertEquals(5L, zipped.state.first.value)
        assertEquals(setOf("alpha", "beta"), zipped.state.second.elements)
    }

    @Test
    fun `zip result is idempotent under embroider`() {
        val a = CoordinationFree(GCounter.of(r1 to 3L))
        val b = CoordinationFree(GSet.of("x"))
        val zipped = a.zip(b)

        assertEquals(zipped.state, zipped.embroider(zipped).state)
    }

    @Test
    fun `zip result is commutative under embroider`() {
        val counterA = CoordinationFree(GCounter.of(r1 to 2L))
        val counterB = CoordinationFree(GCounter.of(r2 to 8L))
        val setA = CoordinationFree(GSet.of("x"))
        val setB = CoordinationFree(GSet.of("y"))

        val zippedA = counterA.zip(setA)
        val zippedB = counterB.zip(setB)

        assertEquals(zippedA.embroider(zippedB).state, zippedB.embroider(zippedA).state)
    }

    @Test
    fun `zip result is associative under embroider`() {
        val r3 = ReplicaId("r3")
        val zA = CoordinationFree(GCounter.of(r1 to 1L)).zip(CoordinationFree(GSet.of("a")))
        val zB = CoordinationFree(GCounter.of(r2 to 2L)).zip(CoordinationFree(GSet.of("b")))
        val zC = CoordinationFree(GCounter.of(r3 to 3L)).zip(CoordinationFree(GSet.of("c")))

        val leftAssoc = zA.embroider(zB).embroider(zC)
        val rightAssoc = zA.embroider(zB.embroider(zC))

        assertEquals(leftAssoc.state, rightAssoc.state)
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
        val r3 = ReplicaId("r3")
        val a = CoordinationFree(GCounter.of(r1 to 10L))
        val b = CoordinationFree(GCounter.of(r2 to 20L))
        val c = CoordinationFree(GCounter.of(r3 to 30L))

        val result = joinAllOrNull(listOf(a, b, c))
        assertEquals(60L, result?.state?.value)
    }
}
