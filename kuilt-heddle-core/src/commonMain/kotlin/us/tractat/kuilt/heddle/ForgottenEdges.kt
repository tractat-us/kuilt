package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.CanonicalMapSerializer
import us.tractat.kuilt.crdt.ReplicaId

/**
 * What an [EntitlementLedger] keeps of the edges it has forgotten (#3192, design §5.5): the totals
 * their slots added up to, folded, so the books still balance once the slots are gone.
 *
 * A retired leaf edge's history counts in three places, and each has a fold here:
 * - [outflow]: per parent, per replica, the `effIssued − returned` of its forgotten children, the
 *   term [EntitlementLedger.holdings] subtracts at the parent;
 * - [leafSpend]: per replica, the effective leaf spend of the forgotten edges, the term
 *   [EntitlementLedger.leafSpentTotal] sums for conservation;
 * - [generationFloor]: the highest topology generation any forgotten edge carried, `-1` for none,
 *   so a new edge is never numbered at or below one the ledger once held.
 *
 * A parent appears in [outflow] once any of its children is forgotten, even when what it folded
 * nets to zero, because "this group has child edges" is part of what a retired child meant.
 *
 * It is the ledger's seventeenth component, and the only one that is not a join: forgetting removes
 * slots, so [EntitlementLedger.piece] refuses to merge two different folds. It is scoped to a single
 * writer, the way §10 invariant 4's quiesced edge is. Every map goes through
 * [CanonicalMapSerializer], so equal folds encode to equal bytes. A ledger with nothing forgotten
 * holds [NONE], which a default `Json` or `Cbor` does not encode, so its bytes are what they were
 * before this component existed. It is carried by [LocalHeddleSnapshot] format 2.
 */
@Serializable
internal data class ForgottenEdges(
    @Serializable(with = CanonicalMapSerializer::class)
    val outflow: Map<GroupId, @Serializable(with = CanonicalMapSerializer::class) Map<ReplicaId, Long>> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val leafSpend: Map<ReplicaId, Long> = emptyMap(),
    val generationFloor: Long = -1L,
) {
    init {
        require(generationFloor >= -1L) { "a forgotten generation floor is -1 or a generation, was $generationFloor" }
    }

    /** Whether nothing has been forgotten. */
    fun isEmpty(): Boolean = outflow.isEmpty() && leafSpend.isEmpty() && generationFloor == -1L

    /** Every replica a fold names. */
    fun replicas(): Set<ReplicaId> = HashSet<ReplicaId>().apply {
        for (row in outflow.values) addAll(row.keys)
        addAll(leafSpend.keys)
    }

    companion object {
        /** Nothing forgotten: the default, and the value every replicated ledger holds. */
        val NONE: ForgottenEdges = ForgottenEdges()
    }
}

/**
 * Why [EntitlementLedger.forget] would refuse one edge, checked in this order. An edge is
 * forgettable when none applies. Test support reads it to tell which check a refusal came from.
 */
internal enum class ForgetRefusal {
    /** No component names the edge. */
    UNKNOWN,

    /** The edge has two records, or none: [LedgerConflict.RecordDivergence] owns it. */
    NOT_ONE_RECORD,

    /** The edge is ACTIVE or CLOSING: only a RETIRED or an unused PREPARED edge is forgotten. */
    LIVE,

    /** The edge's child has child edges, live, retired or forgotten: leaves go first. */
    CHILD_HAS_CHILD_EDGES,

    /** A transfer row, base or relocation, sits at the edge's path, and would lose its key. */
    TRANSFER_ROWS,

    /** A PREPARED edge that carries a counter or a gauge, which a prepared edge never does honestly. */
    PREPARED_CARRIES_HISTORY,

    /** Charged plus returned exceeds issued: [LedgerConflict.PerEdgeSafety], which nothing could report afterwards. */
    PER_EDGE_SAFETY,

    /** A negative effective spend: [LedgerConflict.NegativeEffectiveSpend]. */
    NEGATIVE_EFFECTIVE_SPEND,

    /** Retired with units still outstanding: [LedgerConflict.ClosureViolation]. */
    OUTSTANDING,

    /** A replica would still hold something at the edge if it were live: a strand a move would carry. */
    STRANDED,

    /** The edge's child holds something: it is still in use under another edge. */
    CHILD_HOLDS,
}
