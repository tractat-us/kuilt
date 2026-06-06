package us.tractat.kuilt.crdt.refmodel

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Reference implementation of an add-wins observed-remove map, written without
 * any DotStore machinery. Correct by direct inspection of the causal semantics.
 *
 * A [put] is additive on the value lattice: if the key already exists, the new
 * value is [Quilted.piece]d with the existing one (mirrors [us.tractat.kuilt.crdt.ORMap.put]).
 * A [remove] drops the key's tags, tombstoning it in the causal context. On [merge],
 * add-wins: a tag survives iff the other side has not witnessed-and-removed it.
 *
 * Test-only. NOT a [Quilted]. Used for dual-track comparison against
 * [us.tractat.kuilt.crdt.ORMap].
 */
internal class NaiveORMap<K, V : Quilted<V>> private constructor(
    private val tags: Map<K, Set<Dot>>,
    private val values: Map<K, V>,
    private val seenDots: Set<Dot>,
    private val nextSeqPerReplica: Map<ReplicaId, Long>,
) {
    val keys: Set<K> get() = tags.keys

    operator fun get(key: K): V? = values[key]

    /** Put [value] under [key]. If the key is already present, pieces the values. */
    fun put(replica: ReplicaId, key: K, value: V): NaiveORMap<K, V> {
        val nextSeq = (nextSeqPerReplica[replica] ?: 0L) + 1L
        val newDot = Dot(replica, nextSeq)
        val mergedValue = values[key]?.piece(value) ?: value
        return NaiveORMap(
            tags = tags + (key to setOf(newDot)),
            values = values + (key to mergedValue),
            seenDots = seenDots + newDot,
            nextSeqPerReplica = nextSeqPerReplica + (replica to nextSeq),
        )
    }

    fun remove(key: K): NaiveORMap<K, V> {
        if (key !in tags) return this
        return NaiveORMap(
            tags = tags - key,
            values = values - key,
            seenDots = seenDots,
            nextSeqPerReplica = nextSeqPerReplica,
        )
    }

    /**
     * Add-wins causal merge: a key's tags survive by the same rule as NaiveORSet.
     * When a key is present on both sides, values are pieced together.
     */
    fun merge(other: NaiveORMap<K, V>): NaiveORMap<K, V> {
        val newTags = mutableMapOf<K, Set<Dot>>()
        val newValues = mutableMapOf<K, V>()
        for (k in tags.keys + other.tags.keys) {
            val mine = tags[k] ?: emptySet()
            val theirs = other.tags[k] ?: emptySet()
            val kept = (mine intersect theirs) + (mine - other.seenDots) + (theirs - seenDots)
            if (kept.isNotEmpty()) {
                newTags[k] = kept
                newValues[k] = mergeValues(values[k], other.values[k])
            }
        }
        val mergedSeen = seenDots + other.seenDots
        val mergedSeqs = buildMap {
            putAll(nextSeqPerReplica)
            for ((r, s) in other.nextSeqPerReplica) put(r, maxOf(this[r] ?: 0L, s))
        }
        return NaiveORMap(newTags, newValues, mergedSeen, mergedSeqs)
    }

    private fun mergeValues(left: V?, right: V?): V = when {
        left != null && right != null -> left.piece(right)
        left != null -> left
        else -> right!!
    }

    companion object {
        fun <K, V : Quilted<V>> empty(): NaiveORMap<K, V> =
            NaiveORMap(emptyMap(), emptyMap(), emptySet(), emptyMap())
    }
}
