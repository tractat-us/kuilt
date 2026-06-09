package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the per-author high-water [VersionVector] (#262). */
class VersionVectorTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun getReturnsZeroForUnseenAuthor() {
        assertEquals(0L, VersionVector.EMPTY[a])
        assertEquals(3L, VersionVector.of(mapOf(a to 3L))[a])
    }

    @Test
    fun ofDropsNonPositiveSoEqualityIsCanonical() {
        assertEquals(VersionVector.EMPTY, VersionVector.of(mapOf(a to 0L, b to -1L)))
        assertEquals(VersionVector.of(mapOf(a to 2L)), VersionVector.of(mapOf(a to 2L, b to 0L)))
    }

    @Test
    fun dominatesIsPerAuthorPointwise() {
        val higher = VersionVector.of(mapOf(a to 3L, b to 2L))
        val lower = VersionVector.of(mapOf(a to 3L, b to 1L))
        assertTrue(higher.dominates(lower))
        assertFalse(lower.dominates(higher))
        assertTrue(VersionVector.EMPTY.dominates(VersionVector.EMPTY))
        assertFalse(VersionVector.EMPTY.dominates(VersionVector.of(mapOf(a to 1L))))
    }

    @Test
    fun containsTestsASingleDot() {
        val vv = VersionVector.of(mapOf(a to 2L))
        assertTrue(vv.contains(Dot(a, 2L)))
        assertFalse(vv.contains(Dot(a, 3L)))
        assertFalse(vv.contains(Dot(b, 1L)))
    }

    @Test
    fun floorWithIsElementwiseMin() {
        val x = VersionVector.of(mapOf(a to 3L, b to 1L))
        val y = VersionVector.of(mapOf(a to 2L, b to 5L))
        assertEquals(VersionVector.of(mapOf(a to 2L, b to 1L)), x.floorWith(y))
        // Author present in only one operand floors to 0 (absent) → dropped by `of`.
        assertEquals(VersionVector.EMPTY, VersionVector.of(mapOf(a to 4L)).floorWith(VersionVector.EMPTY))
    }

    @Test
    fun ceilWithIsElementwiseMax() {
        val x = VersionVector.of(mapOf(a to 3L, b to 1L))
        val y = VersionVector.of(mapOf(a to 2L, b to 5L))
        assertEquals(VersionVector.of(mapOf(a to 3L, b to 5L)), x.ceilWith(y))
        assertEquals(VersionVector.of(mapOf(a to 4L)), VersionVector.of(mapOf(a to 4L)).ceilWith(VersionVector.EMPTY))
    }
}
