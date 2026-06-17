package us.tractat.kuilt.quilter

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [contiguousFrontier] — the gap-free per-author high-water that becomes the
 * delivered version vector (#268). A gap truncates an author at the gap.
 */
class ContiguousFrontierTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun emptyDotsYieldEmptyVector() {
        assertEquals(VersionVector.EMPTY, contiguousFrontier(emptySet()))
    }

    @Test
    fun contiguousRunBecomesItsHighWater() {
        val dots = setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L))
        assertEquals(VersionVector.of(mapOf(a to 3L)), contiguousFrontier(dots))
    }

    @Test
    fun gapTruncatesAtTheGap() {
        // dots {1,2,4} → frontier 2 (4 is past the gap at 3).
        val dots = setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 4L))
        assertEquals(VersionVector.of(mapOf(a to 2L)), contiguousFrontier(dots))
    }

    @Test
    fun missingSeqOneContributesNothing() {
        // No seq 1 for a ⇒ a reads as 0 (omitted from the canonical vector).
        val dots = setOf(Dot(a, 2L), Dot(a, 3L))
        assertEquals(VersionVector.EMPTY, contiguousFrontier(dots))
    }

    @Test
    fun authorsAreIndependent() {
        val dots = setOf(Dot(a, 1L), Dot(a, 2L), Dot(b, 1L), Dot(b, 3L))
        assertEquals(VersionVector.of(mapOf(a to 2L, b to 1L)), contiguousFrontier(dots))
    }
}
