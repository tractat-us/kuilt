/**
 * Tests for the roster-derived **k-regular partial view** — Phase 2 of the
 * partial-mesh gossip epic (#657).
 *
 * Each peer derives its active-neighbour set as a seeded random k-out sample of
 * the roster (excluding self), plus a small ordered spare list for reactive
 * healing. Random k-out (rather than a hash ring) is robust against skewed peer-id
 * distributions; the union of every peer's k-out edges is connected with high
 * probability once k ≳ ln(N) (Erdős–Rényi threshold).
 */
package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartialViewTest {

    private fun roster(n: Int): Set<PeerId> = (1..n).map { PeerId("peer-$it") }.toSet()

    @Test
    fun activeViewExcludesSelfAndRespectsK() {
        val all = roster(10)
        val self = all.first()
        val view = partialView(self, all, k = 4, spareCount = 2, random = Random(1))

        assertAll(
            { assertEquals(4, view.active.size, "active view must have exactly k peers") },
            { assertFalse(self in view.active, "active view must exclude self") },
            { assertTrue(view.active.all { it in all }, "active peers must come from the roster") },
        )
    }

    @Test
    fun deterministicForSameSeed() {
        val all = roster(20)
        val self = all.first()
        val a = partialView(self, all, k = 5, spareCount = 3, random = Random(42))
        val b = partialView(self, all, k = 5, spareCount = 3, random = Random(42))
        assertEquals(a, b, "same (self, roster, k, seed) must yield an identical partial view")
    }

    @Test
    fun sparesAreDisjointFromActiveAndBounded() {
        val all = roster(10)
        val self = all.first()
        val view = partialView(self, all, k = 4, spareCount = 3, random = Random(7))

        assertAll(
            { assertEquals(3, view.spares.size, "spare list sized to spareCount when peers remain") },
            { assertTrue(view.spares.none { it in view.active }, "spares must be disjoint from active") },
            { assertFalse(self in view.spares, "spares must exclude self") },
        )
    }

    @Test
    fun activeAndSparesCappedByAvailablePeers() {
        val all = roster(3) // self + 2 others
        val self = all.first()
        val view = partialView(self, all, k = 4, spareCount = 3, random = Random(3))

        assertAll(
            { assertEquals(2, view.active.size, "active capped at available non-self peers") },
            { assertTrue(view.spares.isEmpty(), "no peers left for spares once active takes them all") },
        )
    }

    @Test
    fun recommendedActiveViewSizeFollowsLnPlusTwoFlooredAtFour() {
        assertAll(
            { assertEquals(4, recommendedActiveViewSize(2), "floored at 4 for tiny rooms") },
            { assertEquals(4, recommendedActiveViewSize(5), "ceil(ln5)=2, +2=4") },
            { assertEquals(5, recommendedActiveViewSize(10), "ceil(ln10)=3, +2=5") },
            { assertEquals(6, recommendedActiveViewSize(50), "ceil(ln50)=4, +2=6") },
            { assertEquals(7, recommendedActiveViewSize(100), "ceil(ln100)=5, +2=7") },
        )
    }

    /**
     * The overlay property that matters: with each peer independently drawing a
     * k-out active view (k = recommended for N), the union of all edges — treated
     * as undirected, since gossip flows both ways — is **connected**. Verified over
     * several seeds so it's not a lucky draw.
     */
    @Test
    fun unionOfKOutViewsIsConnected() {
        val n = 50
        val all = roster(n)
        val k = recommendedActiveViewSize(n)

        assertAll(
            *(1..10).map { seed ->
                {
                    val adjacency = mutableMapOf<PeerId, MutableSet<PeerId>>()
                    all.forEach { adjacency[it] = mutableSetOf() }
                    all.forEachIndexed { i, self ->
                        // independent per-peer seed (production seeds from peer identity)
                        val view = partialView(self, all, k, spareCount = 2, random = Random(seed * 1000L + i))
                        view.active.forEach { nbr ->
                            adjacency.getValue(self) += nbr
                            adjacency.getValue(nbr) += self // undirected
                        }
                    }
                    assertTrue(
                        isConnected(adjacency, all),
                        "seed=$seed: union of k-out views (k=$k, N=$n) must be connected",
                    )
                }
            }.toTypedArray(),
        )
    }

    private fun isConnected(adjacency: Map<PeerId, Set<PeerId>>, all: Set<PeerId>): Boolean {
        val start = all.first()
        val seen = mutableSetOf(start)
        val stack = ArrayDeque<PeerId>().apply { add(start) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (nbr in adjacency[cur].orEmpty()) {
                if (seen.add(nbr)) stack.add(nbr)
            }
        }
        return seen.size == all.size
    }
}
