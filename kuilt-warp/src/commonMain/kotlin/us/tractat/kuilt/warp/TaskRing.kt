package us.tractat.kuilt.warp

import us.tractat.kuilt.core.PeerId
import kotlin.math.absoluteValue

/**
 * A consistent-hash ring that assigns tasks to peers in a deterministic, balanced way.
 *
 * The ring IS the roster. Given a snapshot of the current peer set, every node computes
 * the same ring and the same task-to-peer mapping — no coordination required. Tasks are
 * assigned by finding the first peer clockwise of the task's hash position on the ring.
 *
 * **Virtual nodes** — each peer occupies [vnodeCount] positions on the ring. More virtual
 * nodes → more even load distribution at the cost of a slightly larger ring. 150 is a good
 * default for groups up to ~20 peers.
 *
 * **Failover** — when an owner becomes unreachable, call [successor] with the owner (and
 * any other down peers) in the `excluding` set. The next peer clockwise that is not excluded
 * becomes the failover owner deterministically.
 *
 * **Pluggable roster** — construct via [RosterSnapshot.toTaskRing] to bind to a specific
 * roster source. Two sources are available out of the box:
 * - Raft voter membership (strong consistency, zero steady-state dups)
 * - Session room roster (eventual consistency, cheaper for low-churn groups)
 *
 * This class is pure and synchronous — it holds a snapshot; callers rebuild it when the
 * roster changes.
 *
 * @param peers The current peer roster. Empty → [owner] always returns `null`.
 * @param vnodeCount Virtual nodes per peer. Default 150.
 * @param seed Deterministic seed for vnode placement. Use a stable constant across all
 *   nodes in the same session so every node produces the same ring.
 */
public class TaskRing(
    peers: Set<PeerId>,
    public val vnodeCount: Int = 150,
    seed: Long = 0L,
) {
    /**
     * The sorted virtual-node ring.
     *
     * Each entry is `(ringPosition, peerId)`.  Sorted ascending by position so the
     * "first clockwise" lookup is a binary search.
     */
    private val ring: List<RingEntry> = buildRing(peers, vnodeCount, seed)

    /**
     * Returns the peer that owns [taskId] under the current roster, or `null` if the
     * roster is empty.
     *
     * The owner is the first peer clockwise of `hash(taskId)` on the ring.
     * Deterministic: same roster + same seed → same result for every peer in the session.
     */
    public fun owner(taskId: TaskId): PeerId? = firstClockwise(taskId, excluding = emptySet())

    /**
     * Returns the first peer clockwise of [taskId]'s position that is NOT in [excluding],
     * or `null` if all roster peers are excluded.
     *
     * Use this as the failover hook: pass the unreachable owner (and any other known-down
     * peers) in [excluding]. The result is deterministic for the same inputs.
     */
    public fun successor(taskId: TaskId, excluding: Set<PeerId>): PeerId? =
        firstClockwise(taskId, excluding = excluding)

    // -------------------------------------------------------------------------
    // Internal — ring search
    // -------------------------------------------------------------------------

    private fun firstClockwise(taskId: TaskId, excluding: Set<PeerId>): PeerId? {
        if (ring.isEmpty()) return null
        val hash = taskHash(taskId)
        val startIdx = lowerBound(hash)
        repeat(ring.size) { offset ->
            val candidate = ring[(startIdx + offset) % ring.size].peer
            if (candidate !in excluding) return candidate
        }
        return null
    }

    /**
     * Index of the first ring entry whose position is >= [hash], wrapping to 0 if none.
     * This is the "first clockwise" position — the ring wraps around at the top.
     */
    private fun lowerBound(hash: Int): Int {
        var lo = 0
        var hi = ring.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ring[mid].position < hash) lo = mid + 1 else hi = mid
        }
        return if (lo >= ring.size) 0 else lo
    }

    // -------------------------------------------------------------------------
    // Ring construction
    // -------------------------------------------------------------------------

    private data class RingEntry(val position: Int, val peer: PeerId)

    private companion object {
        /**
         * Builds and sorts the virtual-node ring.
         *
         * Virtual-node positions are derived from a well-mixed hash of
         * `(seed XOR hash("peer:vnode"))`. Sorting peers by ID first ensures the ring is
         * identical regardless of the iteration order of the input [Set].
         */
        fun buildRing(peers: Set<PeerId>, vnodeCount: Int, seed: Long): List<RingEntry> {
            // Sort peers by ID first for stable iteration order regardless of Set impl.
            val sortedPeers = peers.sortedBy { it.value }
            return sortedPeers
                .flatMap { peer -> vnodeEntries(peer, vnodeCount, seed) }
                .sortedBy { it.position }
        }

        fun vnodeEntries(peer: PeerId, vnodeCount: Int, seed: Long): List<RingEntry> =
            (0 until vnodeCount).map { vnode ->
                RingEntry(vnodePosition(peer, vnode, seed), peer)
            }

        /**
         * Positions a virtual node on the ring using a Murmur3-inspired finalizer.
         *
         * We combine the peer+vnode string hash with the [seed] and run it through an
         * integer avalanche (fmix32) to distribute the positions uniformly across the
         * full Int range. This avoids the clustering that Kotlin's `String.hashCode()`
         * produces for short, structured strings like "a:0", "a:1", etc.
         */
        fun vnodePosition(peer: PeerId, vnode: Int, seed: Long): Int {
            val raw = "${peer.value}:$vnode".hashCode()
            val mixed = fmix32(raw xor seed.toInt())
            return mixed.absoluteValue
        }

        /**
         * Murmur3 integer finalizer — good avalanche mixing, no external deps.
         *
         * Converts a weakly-distributed input (e.g. `String.hashCode()` of short strings)
         * into one where every output bit depends on every input bit.
         */
        fun fmix32(h: Int): Int {
            var x = h
            x = x xor (x ushr 16)
            x *= -2048144789  // 0xff51afd7ed558ccd.toInt()
            x = x xor (x ushr 13)
            x *= -1028477387  // 0xc4ceb9fe1a85ec53.toInt()
            x = x xor (x ushr 16)
            return x
        }

        fun taskHash(taskId: TaskId): Int = fmix32(taskId.value.hashCode()).absoluteValue
    }
}
