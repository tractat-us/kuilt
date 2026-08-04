package us.tractat.kuilt.crdt

/**
 * How the [OpLogEngine] sees one operation, independent of the concrete op-log CRDT.
 *
 * Both [RgaOp] and [FugueOp] map onto these three shapes: an [Insert] mints a new
 * id (and thus one causal [Dot]); a [Remove] tombstones an existing id and mints no
 * dot (it reuses its target insert's id); a [Compact] records the ids it garbage-
 * collected so the delivered frontier survives the trim.
 */
internal sealed interface LogOp<out Id> {
    /** An insert that mints [id] — contributes exactly one causal [Dot]. */
    data class Insert<Id>(val id: Id) : LogOp<Id>

    /** A tombstone of [id]. Contributes no dot (it reuses the insert's id). */
    data class Remove<Id>(val id: Id) : LogOp<Id>

    /** A compaction record carrying the [compactedIds] it garbage-collected. */
    data class Compact<Id>(val compactedIds: Set<Id>) : LogOp<Id>
}

/**
 * The shared op-log + causal-stability core behind the op-log CRDTs [Rga] and
 * [Fugue] (see `docs/op-log-crdt-compaction.md`). Both carry a near-identical
 * engine: an op-log whose Insert/Remove/Compact ops are trimmed by a
 * causal-stability barrier, plus a dense per-author delivery counter that must
 * survive garbage collection. This class captures the parts of that engine that
 * are pure functions of the op-log — free of any concrete-type or derived-cache
 * construction — parameterized over the id ([Id]) and op ([Op]) types.
 *
 * The concrete CRDT supplies two adapters:
 * - [view] classifies one [Op] as an [LogOp] (Insert / Remove / Compact).
 * - [dotOf] projects an [Id] to its causal [Dot] `(replica, seq)`.
 *
 * Everything the engine exposes — [purge], [purgeAndRecord], [causalDots],
 * [maxSeqByReplica] — is defined in terms of those two adapters, so the two CRDTs
 * share one implementation of the delivery-frontier and dense-seq logic (including
 * the GC-survives-a-self-compaction subtlety, #639) rather than duplicating it.
 *
 * @param Id the element-identity type (`RgaId` / `FugueId`).
 * @param Op the op type (`RgaOp<V>` / `FugueOp<V>`).
 */
internal class OpLogEngine<Id : Any, Op : Any>(
    private val view: (Op) -> LogOp<Id>,
    private val dotOf: (Id) -> Dot,
) {
    /**
     * Drop every Insert and Remove op whose id is in [gcIds]; keep every Compact op
     * intact. This is the log-trim step shared by `piece`, `compact`, and the
     * remote `Compact`-apply path.
     */
    fun purge(ops: Set<Op>, gcIds: Set<Id>): Set<Op> =
        ops.filterTo(mutableSetOf()) { op ->
            when (val v = view(op)) {
                is LogOp.Insert -> v.id !in gcIds
                is LogOp.Remove -> v.id !in gcIds
                is LogOp.Compact -> true
            }
        }

    /**
     * [purge] the Insert/Remove ops for [gcIds], then add [compactOp] to the log so
     * the compaction is retained and re-applies on a later merge with a peer that
     * hasn't compacted yet.
     */
    fun purgeAndRecord(ops: Set<Op>, gcIds: Set<Id>, compactOp: Op): Set<Op> =
        purge(ops, gcIds) + compactOp

    /**
     * The causal [Dot]s this op-log has delivered: every `Insert`'s own dot plus
     * every dot recorded in a `Compact` op. A `Remove` contributes nothing (it
     * reuses its target insert's id and mints no dot). Re-emitting the compacted
     * dots keeps the contiguous delivered frontier gap-free across GC.
     */
    fun causalDots(ops: Set<Op>): Set<Dot> =
        ops.asSequence().flatMap { deliveredDots(it) }.toSet()

    /**
     * Highest dense per-author `seq` seen in [ops], folding in **compacted** ids as
     * well as live inserts. A self-compaction purges a replica's own `Insert` from
     * the log, so scanning only surviving inserts would regress the per-author
     * high-water and let a minted seq be reused (#639); the compacted ids survive in
     * the retained `Compact` op's recorded dots, so the true high-water is recoverable.
     */
    fun maxSeqByReplica(ops: Set<Op>): Map<ReplicaId, Long> {
        val result = mutableMapOf<ReplicaId, Long>()
        for (op in ops) {
            for (dot in deliveredDots(op)) {
                val current = result[dot.replica]
                if (current == null || dot.seq > current) result[dot.replica] = dot.seq
            }
        }
        return result
    }

    /** The causal dots one op contributes to the delivered frontier and the seq high-water. */
    private fun deliveredDots(op: Op): Sequence<Dot> =
        when (val v = view(op)) {
            is LogOp.Insert -> sequenceOf(dotOf(v.id))
            is LogOp.Compact -> v.compactedIds.asSequence().map(dotOf)
            is LogOp.Remove -> emptySequence()
        }
}

/**
 * Order two compaction records by the **full sorted key-list** of their position maps, compared
 * lexicographically and then by length. Shared by `RgaSerializer` and `FugueSerializer`, which both
 * need a deterministic order between several `Compact` ops in one log.
 *
 * A `Compact` op carries no element id of its own, so it cannot be ordered by the id that orders
 * `Insert` and `Remove`. Surviving `Compact` ops on a well-formed replica have **disjoint** key
 * sets — each id is compacted into at most one op — so `keys.minOrNull()` would already be
 * tie-free. Walking the whole sorted key-list instead guards a malformed remote that violates the
 * disjointness invariant, where `minOrNull()` could tie and fall back to set-iteration order: the
 * exact nondeterminism #713 fixed. O(N) in the compacted-id count, and `Compact` ops are rare and
 * small.
 *
 * Reads only `keys.sorted()`, never the maps' iteration order, so it composes with — rather than
 * duplicates — the `CanonicalMapSerializer` that canonicalises the order *within* each map (#1978).
 *
 * **Residual tie.** Two `Compact` ops with equal key-lists but different values still compare
 * equal and fall back to input order. That needs a malformed remote — a given id's recorded
 * position is fixed when its `Insert` is created, so two well-formed replicas always agree on it.
 */
internal fun <Id : Comparable<Id>> compareCompactPositions(a: Map<Id, Id>, b: Map<Id, Id>): Int {
    val keysA = a.keys.sorted()
    val keysB = b.keys.sorted()
    val minLen = minOf(keysA.size, keysB.size)
    for (i in 0 until minLen) {
        val cmp = keysA[i].compareTo(keysB[i])
        if (cmp != 0) return cmp
    }
    return keysA.size - keysB.size
}

/**
 * Chain-walk [positions] from [start] to the nearest ancestor that is still
 * [present] — or the [head] sentinel. This is the positional-reroot resolution
 * shared by [Rga]'s `computeSequence` and [Fugue]'s `buildTree`: when an insert's
 * predecessor/parent has been garbage-collected, its successor reattaches to the
 * GC'd element's own surviving ancestor rather than floating to [head], preserving
 * the relative order of surviving elements across compaction (Rga #293, Fugue #714).
 *
 * [positions] is the union of every [LogOp.Compact] op's recorded position map
 * (`compactPositions`); [present] is the set of live (non-compacted) ids.
 *
 * The walk is bounded: compaction only removes causally-stable elements with no
 * surviving successor, so [positions] is acyclic and terminates at [head] or a
 * present id within O(compacted depth) steps.
 *
 * Pure `Id`-and-`Map<Id, Id>` logic — it needs neither the op-view nor dot adapters,
 * so it lives as a free function beside [OpLogEngine] rather than as a method on it.
 */
internal fun <Id> nearestPresentAncestor(
    start: Id,
    head: Id,
    present: Set<Id>,
    positions: Map<Id, Id>,
): Id {
    var cur = start
    while (cur != head && cur !in present) cur = positions[cur] ?: head
    return cur
}
