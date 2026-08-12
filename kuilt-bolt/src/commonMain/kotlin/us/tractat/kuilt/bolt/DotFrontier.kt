package us.tractat.kuilt.bolt

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Which insert dots a [BoltDecorator] has already archived — held as **contiguous runs of `seq` per
 * author**, so a peer's whole log costs one entry rather than one per operation.
 *
 * An `Insert` mints exactly one causal [Dot], so "have I archived this insert?" is a question about
 * a set of `(replica, seq)` pairs. That set is dense almost everywhere: a peer offers a window of
 * its own log, and one author's contribution to it is a contiguous stretch of its own sequence
 * numbers. Storing the stretch — `alice: 4_812…19_140` — instead of its members is what turns the
 * memory from *one entry per archived operation* into *one entry per author*.
 *
 * ### Runs rather than a high-water mark plus a gap set
 *
 * The obvious shape is a `VersionVector`-style high-water per author plus a set of above-floor dots
 * waiting for the gap below them to fill. It is the wrong one here, for a reason this module
 * already lives with: **[BoltDecorator] rebuilds nothing when a process opens an archive.** So a
 * server restarted after its peers have windowed their logs meets author `alice` at `seq = 4_812`
 * and *never* sees `1…4_811` — they were compacted away before this process existed. A high-water
 * pinned at `0` below that hole would hold every dot above it individually, which is exactly the
 * O(archived operations) growth the runs exist to avoid. A run does not care: the hole is the space
 * *between* two runs, and costs nothing to leave open forever.
 *
 * That is the answer to "what if a peer's dots arrive with a hole that never fills" — nothing. It
 * is not a degraded case; it is the common one after any restart.
 *
 * ### Bounded, because "few runs" is a fact about practice and not a guarantee
 *
 * Runs are O(authors + unfilled holes). Both are small in every case this module is built for and
 * neither is bounded *by construction* — an archive's author set grows with the fleet, and a peer
 * that compacted a scattered set of ids rather than a prefix leaves scattered holes. So [maxRuns]
 * caps the total and [trim] evicts down to it.
 *
 * **Eviction drops a run; it never merges across a hole.** Dropping one means the inserts it
 * covered are offered again and archived a second time — bytes, and folding an op-log CRDT's
 * operation twice is idempotent. Merging two runs across the hole between them would instead make
 * the frontier claim dots it never archived, and those inserts would be suppressed forever: a
 * *lost record*, not a duplicated one. The two directions are not symmetric and only one of them is
 * available.
 *
 * The victim is the **shortest** run — the one whose entry is buying the fewest suppressed
 * re-offers, and so the cheapest to rebuild. Recency, the rule [BoltDecorator]'s removal window
 * uses, discriminates nothing here: anti-entropy re-offers every peer's log every round, so under
 * gossip every run is equally recent.
 *
 * Not thread-safe, and deliberately so — [BoltDecorator] holds it under its own lock.
 *
 * @param maxRuns the most runs to hold, across every author. `0` disables suppression entirely.
 */
internal class DotFrontier(private val maxRuns: Int) {

    init {
        require(maxRuns >= 0) { "maxRuns must not be negative, was $maxRuns" }
    }

    /** Per author, the runs of archived `seq`, sorted ascending and kept disjoint and non-adjacent. */
    private val byAuthor = HashMap<ReplicaId, MutableList<SeqRun>>()

    /** How many runs are held in total — the quantity [maxRuns] bounds. */
    var runCount: Int = 0
        private set

    /** How many authors are held. Never larger than [runCount]. */
    val authorCount: Int get() = byAuthor.size

    /** Whether [dot]'s insert has already been archived. */
    fun contains(dot: Dot): Boolean {
        val runs = byAuthor[dot.replica] ?: return false
        val at = floorIndex(runs, dot.seq)
        return at >= 0 && dot.seq <= runs[at].last
    }

    /**
     * Record [dot] as archived, extending or joining the runs around it. Answers whether it was new.
     *
     * Adding the seq that separates two runs closes the hole and merges them, which is how a
     * frontier fragmented by out-of-order delivery collapses back to one run per author as the gaps
     * fill.
     */
    fun add(dot: Dot): Boolean {
        val runs = byAuthor.getOrPut(dot.replica) { mutableListOf() }
        val seq = dot.seq
        val at = floorIndex(runs, seq)
        val below = runs.getOrNull(at)
        if (below != null && seq <= below.last) return false
        val above = runs.getOrNull(at + 1)
        val joinsBelow = below != null && below.last == seq - 1
        val joinsAbove = above != null && above.first == seq + 1
        when {
            joinsBelow && joinsAbove -> {
                below.last = above.last
                runs.removeAt(at + 1)
                runCount--
            }
            joinsBelow -> below.last = seq
            joinsAbove -> above.first = seq
            else -> {
                runs.add(at + 1, SeqRun(seq, seq))
                runCount++
            }
        }
        return true
    }

    /**
     * Evict shortest-run-first until at most [maxRuns] remain.
     *
     * Called once per committed append rather than per dot, so a batch that closes several holes is
     * measured after they have merged rather than while it is transiently over the cap.
     */
    fun trim() {
        if (runCount <= maxRuns) return
        val kept = byAuthor.entries
            .flatMap { (author, runs) -> runs.map { author to it } }
            // Deterministic across platforms: length, then author, then position. A hash-ordered
            // tie-break would make an eviction test pass on one target and fail on another.
            .sortedWith(
                compareByDescending<Pair<ReplicaId, SeqRun>> { (_, run) -> run.length }
                    .thenBy { (author, _) -> author }
                    .thenBy { (_, run) -> run.first },
            )
            .take(maxRuns)
        byAuthor.clear()
        kept.forEach { (author, run) -> byAuthor.getOrPut(author) { mutableListOf() }.add(run) }
        byAuthor.values.forEach { runs -> runs.sortBy { it.first } }
        runCount = kept.size
    }

    /** The greatest index whose run starts at or below [seq], or `-1` if every run starts above it. */
    private fun floorIndex(runs: List<SeqRun>, seq: Long): Int {
        var low = 0
        var high = runs.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (runs[mid].first <= seq) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** One author's archived `seq` from [first] to [last], both ends inclusive. */
    private class SeqRun(var first: Long, var last: Long) {
        val length: Long get() = last - first + 1
    }
}
