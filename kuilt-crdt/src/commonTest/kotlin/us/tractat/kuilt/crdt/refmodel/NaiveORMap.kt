package us.tractat.kuilt.crdt.refmodel

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Reference implementation of an add-wins observed-remove map, written without
 * any DotStore machinery. Correct by direct inspection of the causal semantics.
 *
 * **Each tag carries the write made under it** — `tags[key]` maps a [Dot] to the value written
 * there, and the key's value is every live tag's write, [Quilted.piece]d together. A [put] is
 * therefore additive on the value lattice while replacing only the *putting replica's own* tag,
 * folding that tag's write into the new one. A [remove] drops the key's tags, tombstoning them in
 * the causal context, and takes their writes with them. On [merge], add-wins: a tag survives iff
 * the other side has not witnessed-and-removed it.
 *
 * The earlier form of this model kept one value per key beside the tag set, mirroring `ORMap` as it
 * then was. That shape is not a semilattice — one value slot forces a join to blend two operands'
 * writes, and retiring one of the two tags afterwards keeps the blend — so the model reproduced
 * #2086 rather than catching it. A reference model has to be correct by inspection of the
 * *semantics*, not by imitation of the implementation.
 *
 * Test-only. NOT a [Quilted]. Used for dual-track comparison against
 * [us.tractat.kuilt.crdt.ORMap].
 */
internal class NaiveORMap<K, V : Quilted<V>> private constructor(
    private val tags: Map<K, Map<Dot, V>>,
    private val seenDots: Set<Dot>,
    private val nextSeqPerReplica: Map<ReplicaId, Long>,
) {
    val keys: Set<K> get() = tags.keys

    operator fun get(key: K): V? = tags[key]?.let { writes ->
        writes.keys.sorted().map { writes.getValue(it) }.reduceOrNull { l, r -> l.piece(r) }
    }

    /** Put [value] under [key], superseding this replica's own tag and folding its write in. */
    fun put(replica: ReplicaId, key: K, value: V): NaiveORMap<K, V> {
        val nextSeq = (nextSeqPerReplica[replica] ?: 0L) + 1L
        val newDot = Dot(replica, nextSeq)
        val writes = tags[key] ?: emptyMap()
        val folded = writes.keys.filter { it.replica == replica }.sorted()
            .fold(value) { acc, dot -> acc.piece(writes.getValue(dot)) }
        return NaiveORMap(
            tags = tags + (key to (writes.filterKeys { it.replica != replica } + (newDot to folded))),
            seenDots = seenDots + newDot,
            nextSeqPerReplica = nextSeqPerReplica + (replica to nextSeq),
        )
    }

    fun remove(key: K): NaiveORMap<K, V> {
        if (key !in tags) return this
        return NaiveORMap(
            tags = tags - key,
            seenDots = seenDots,
            nextSeqPerReplica = nextSeqPerReplica,
        )
    }

    /**
     * Add-wins causal merge: a key's tags survive by the same rule as NaiveORSet, and each
     * surviving tag brings its own write along.
     */
    fun merge(other: NaiveORMap<K, V>): NaiveORMap<K, V> {
        val newTags = mutableMapOf<K, Map<Dot, V>>()
        for (k in tags.keys + other.tags.keys) {
            val mine = tags[k] ?: emptyMap()
            val theirs = other.tags[k] ?: emptyMap()
            val keptDots = (mine.keys intersect theirs.keys) +
                (mine.keys - other.seenDots) +
                (theirs.keys - seenDots)
            if (keptDots.isNotEmpty()) {
                newTags[k] = keptDots.associateWith { dot ->
                    val l = mine[dot]
                    val r = theirs[dot]
                    when {
                        l != null && r != null -> l.piece(r)
                        l != null -> l
                        else -> checkNotNull(r)
                    }
                }
            }
        }
        val mergedSeen = seenDots + other.seenDots
        val mergedSeqs = buildMap {
            putAll(nextSeqPerReplica)
            for ((r, s) in other.nextSeqPerReplica) put(r, maxOf(this[r] ?: 0L, s))
        }
        return NaiveORMap(newTags, mergedSeen, mergedSeqs)
    }

    companion object {
        fun <K, V : Quilted<V>> empty(): NaiveORMap<K, V> =
            NaiveORMap(emptyMap(), emptySet(), emptyMap())
    }
}
