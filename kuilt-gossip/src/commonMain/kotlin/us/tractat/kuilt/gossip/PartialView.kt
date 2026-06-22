package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

/**
 * A peer's partial view of the gossip overlay.
 *
 * In a partial mesh each peer keeps in touch with only a handful of others —
 * its [active] neighbours — rather than everyone. [spares] is a short ordered
 * standby list: if an active neighbour drops, the next spare is promoted
 * immediately, so the overlay heals without waiting for the full roster to
 * update. (Anti-entropy in `:kuilt-quilter` guarantees convergence during any
 * gap, so the overlay only has to be *usually* connected, not perfectly so.)
 *
 * @property active the ~k neighbours this peer gossips deltas with and GCs against.
 * @property spares disjoint standby candidates promoted on active-neighbour loss.
 */
public data class PartialView(
    public val active: Set<PeerId>,
    public val spares: List<PeerId>,
)

/**
 * Recommended active-view size *k* for a membership of [membershipSize] peers:
 * `k = max(4, ⌈ln N⌉ + 2)`.
 *
 * The `⌈ln N⌉` term tracks the Erdős–Rényi connectivity threshold — a random
 * graph whose nodes each pick ≳ `ln N` neighbours is connected with high
 * probability. The `+2` adds redundancy against simultaneous failures; the floor
 * of 4 keeps small rooms robust. For the tens–low-hundreds target this yields
 * k ≈ 4–7.
 */
public fun recommendedActiveViewSize(membershipSize: Int): Int =
    maxOf(4, ceil(ln(membershipSize.toDouble())).toInt() + 2)

/**
 * Derives this peer's [PartialView] from the full [roster] as a seeded random
 * **k-out** sample (excluding [self]): shuffle the other peers and take the first
 * [k] as [PartialView.active], then up to [spareCount] more as disjoint
 * [PartialView.spares]. Both are capped by the number of available peers.
 *
 * Random k-out (rather than a hash ring) is robust against skewed peer-id
 * distributions. [random] **must be seeded per-peer by the caller** (e.g. from the
 * peer's identity) so peers choose independently — a shared seed would make every
 * peer pick the same neighbours, collapsing the random graph.
 */
public fun partialView(
    self: PeerId,
    roster: Set<PeerId>,
    k: Int,
    spareCount: Int,
    random: Random,
): PartialView {
    val candidates = (roster - self).shuffled(random)
    val active = candidates.take(k).toSet()
    val spares = candidates.drop(active.size).take(spareCount)
    return PartialView(active = active, spares = spares)
}
