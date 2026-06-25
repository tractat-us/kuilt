package us.tractat.kuilt.warp

/**
 * One step in a [Draft] dataflow graph.
 *
 * Every stage carries an [opId] — the symbolic name of the operation it references —
 * and a [coordinationKind] that tags whether the step is safe to apply without
 * coordination ([CoordinationKind.Free]) or requires a consensus round
 * ([CoordinationKind.Coordinated]).
 *
 * The sealed hierarchy encodes the structural rules of CALM:
 * - [Source], [Map], and [Filter] are always [CoordinationKind.Free]: monotone
 *   transformations commute, so they need no agreement.
 * - [Embroider] is always [CoordinationKind.Coordinated]: the single terminal step
 *   that requires consensus (the "embroidery" in the warp metaphor).
 *
 * No op is ever invoked here. Stages store only symbolic references; execution is the
 * responsibility of the E-5 runtime.
 *
 * @see Draft
 * @see CoordinationKind
 */
public sealed class DraftStage {

    /** The symbolic name of the operation this stage references. Code never moves; only names do. */
    public abstract val opId: OpId

    /** Whether this stage requires coordination to apply correctly. */
    public abstract val coordinationKind: CoordinationKind

    /**
     * A source stage — the entry point of a [Draft] pipeline.
     *
     * Represents named data living on peers (a collection, a named view, or any
     * operation that produces elements to be processed downstream). Always
     * [CoordinationKind.Free]: reading from a distributed collection is monotone.
     */
    public data class Source(override val opId: OpId) : DraftStage() {
        override val coordinationKind: CoordinationKind get() = CoordinationKind.Free
    }

    /**
     * A monotone transform stage — maps each element to a new value.
     *
     * The referenced op must be a monotone function: one that preserves the lattice
     * ordering so the pipeline stays convergent. Always [CoordinationKind.Free]:
     * a monotone map commutes with concurrent contributions by construction.
     */
    public data class Map(override val opId: OpId) : DraftStage() {
        override val coordinationKind: CoordinationKind get() = CoordinationKind.Free
    }

    /**
     * A monotone filter stage — retains elements satisfying a predicate.
     *
     * The referenced op must be a monotone predicate so filtering commutes with
     * concurrent contributions. Always [CoordinationKind.Free].
     */
    public data class Filter(override val opId: OpId) : DraftStage() {
        override val coordinationKind: CoordinationKind get() = CoordinationKind.Free
    }

    /**
     * The terminal coordination stage — the single "embroidery" step that requires
     * global agreement.
     *
     * Represents the one point where the pipeline crosses from the coordination-free
     * fast path onto the consensus path. There is at most one [Embroider] stage per
     * [Draft]; placing it last and deferring it as long as possible is the optimizer's
     * primary goal (E-2/E-3). Always [CoordinationKind.Coordinated].
     */
    public data class Embroider(override val opId: OpId) : DraftStage() {
        override val coordinationKind: CoordinationKind get() = CoordinationKind.Coordinated
    }

    /**
     * Two or more adjacent [Map] stages fused into a single pipeline unit by the E-2 optimizer.
     *
     * The E-2 [Draft.fuseAdjacent] rewrite collapses a run of consecutive [Map] stages into
     * one [FusedMap] so the E-5 runtime can apply them in a single pass. All constituent
     * transforms are monotone — per CALM, adjacent monotone stages commute, so fusion is safe
     * by construction and never changes the convergent result.
     *
     * [opIds] holds the original operation names in execution order. [opId] (from
     * [DraftStage]) returns [opIds].first() as a representative identifier; use [opIds] for the
     * complete ordered list. The E-3 cost model should iterate [opIds] to count fused operations.
     *
     * @param opIds ordered list of the original [Map] operation identifiers; must be non-empty.
     */
    public data class FusedMap(public val opIds: List<OpId>) : DraftStage() {
        init { require(opIds.isNotEmpty()) { "FusedMap requires at least one opId" } }
        override val opId: OpId get() = opIds.first()
        override val coordinationKind: CoordinationKind get() = CoordinationKind.Free
    }

    /**
     * Two or more adjacent [Filter] stages fused into a single pipeline unit by the E-2 optimizer.
     *
     * The E-2 [Draft.fuseAdjacent] rewrite collapses a run of consecutive [Filter] stages into
     * one [FusedFilter] so the E-5 runtime can evaluate all predicates in a single pass. All
     * constituent predicates are monotone — per CALM, adjacent monotone stages commute, so fusion
     * is safe by construction and never changes the convergent result.
     *
     * [opIds] holds the original predicate names in execution order. [opId] (from [DraftStage])
     * returns [opIds].first() as a representative identifier; use [opIds] for the complete ordered
     * list. The E-3 cost model should iterate [opIds] to count fused predicates.
     *
     * @param opIds ordered list of the original [Filter] operation identifiers; must be non-empty.
     */
    public data class FusedFilter(public val opIds: List<OpId>) : DraftStage() {
        init { require(opIds.isNotEmpty()) { "FusedFilter requires at least one opId" } }
        override val opId: OpId get() = opIds.first()
        override val coordinationKind: CoordinationKind get() = CoordinationKind.Free
    }
}
