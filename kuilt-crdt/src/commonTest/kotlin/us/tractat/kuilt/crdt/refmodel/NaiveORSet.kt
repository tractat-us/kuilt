package us.tractat.kuilt.crdt.refmodel

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Reference implementation of an add-wins observed-remove set, written without
 * any DotStore machinery. Correct by direct inspection of the causal semantics:
 * each element is tagged with the dot(s) that added it; a remove drops those
 * dots; merge applies add-wins by keeping any dot the other side has not
 * explicitly removed (witnessed but absent → deliberate remove).
 *
 * Test-only. NOT a [us.tractat.kuilt.crdt.Quilted]. Used for dual-track
 * comparison against [us.tractat.kuilt.crdt.ORSet].
 */
internal class NaiveORSet<E> private constructor(
    private val tags: Map<E, Set<Dot>>,
    private val seenDots: Set<Dot>,
    private val nextSeqPerReplica: Map<ReplicaId, Long>,
) {
    val elements: Set<E> get() = tags.keys

    fun contains(e: E): Boolean = e in tags

    fun add(replica: ReplicaId, e: E): NaiveORSet<E> {
        val nextSeq = (nextSeqPerReplica[replica] ?: 0L) + 1L
        val newDot = Dot(replica, nextSeq)
        return NaiveORSet(
            tags = tags + (e to setOf(newDot)),
            seenDots = seenDots + newDot,
            nextSeqPerReplica = nextSeqPerReplica + (replica to nextSeq),
        )
    }

    fun remove(e: E): NaiveORSet<E> {
        if (e !in tags) return this
        return NaiveORSet(
            tags = tags - e,
            seenDots = seenDots,
            nextSeqPerReplica = nextSeqPerReplica,
        )
    }

    /**
     * Add-wins causal merge: a dot survives iff it is live in both, or live in
     * one side and the other side has never witnessed it (not yet received).
     * A dot that the other side has witnessed but does NOT hold was deliberately
     * removed.
     */
    fun merge(other: NaiveORSet<E>): NaiveORSet<E> {
        val mergedTags = mutableMapOf<E, Set<Dot>>()
        for (e in tags.keys + other.tags.keys) {
            val mine = tags[e] ?: emptySet()
            val theirs = other.tags[e] ?: emptySet()
            val kept = (mine intersect theirs) +
                (mine - other.seenDots) +
                (theirs - seenDots)
            if (kept.isNotEmpty()) mergedTags[e] = kept
        }
        val mergedSeen = seenDots + other.seenDots
        val mergedSeqs = buildMap {
            putAll(nextSeqPerReplica)
            for ((r, s) in other.nextSeqPerReplica) put(r, maxOf(getOrDefault(r, 0L), s))
        }
        return NaiveORSet(mergedTags, mergedSeen, mergedSeqs)
    }

    companion object {
        fun <E> empty(): NaiveORSet<E> = NaiveORSet(emptyMap(), emptySet(), emptyMap())
    }
}
