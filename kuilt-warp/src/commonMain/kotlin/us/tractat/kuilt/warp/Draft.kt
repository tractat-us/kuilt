package us.tractat.kuilt.warp

/**
 * An immutable, inspectable dataflow graph that captures a distributed computation
 * without running it.
 *
 * Think of a [Draft] as a recipe, not a meal. It records *what* should happen — a
 * sequence of named operations — but touches no data and invokes no code. This lets
 * a planner inspect, rewrite, and optimise the graph before committing to execution
 * (E-2 rewrites, E-3 cost model, E-5 incremental execution).
 *
 * ## Building a Draft
 *
 * Start with [Warp.shuttle], then chain [map], [filter], and optionally [embroider]:
 *
 * ```kotlin
 * val draft: Draft<ByteArray> = Warp.shuttle(OpId("docs"))
 *     .map(OpId("score"))
 *     .filter(OpId("above-threshold"))
 *     .embroider(OpId("rank"))
 * ```
 *
 * ## Inspecting a Draft
 *
 * - [stages] — the full ordered list of [DraftStage]s as recorded.
 * - [isMonotone] — `true` when every stage is [CoordinationKind.Free]; no consensus
 *   needed. The planner can run the whole pipeline coordination-free.
 * - [embroidery] — the single [DraftStage.Embroider] stage, or `null` if none was
 *   added. The E-2 planner uses this to locate and defer the consensus step.
 *
 * ## The no-execution guarantee
 *
 * A [Draft] stores only symbolic [OpId]s — the names of registered operations. It
 * has no access to an [OpRegistry] and no mechanism to invoke an [Op]. Nothing
 * executes until E-5 provides a runtime.
 *
 * @param T phantom type parameter representing the element type the pipeline would
 *   produce at execution time. Not instantiated in E-1; present for forward
 *   compatibility with E-5 typed wrappers.
 * @see Warp.shuttle
 * @see DraftStage
 */
public class Draft<out T> @PublishedApi internal constructor(
    /** All stages of this dataflow graph in the order they were added. */
    public val stages: List<DraftStage>,
) {

    /**
     * `true` when every stage in this [Draft] is [CoordinationKind.Free] — no
     * consensus step is present. A monotone pipeline converges without any Raft round.
     *
     * The E-3 cost model counts coordination stages; [isMonotone] is the zero-cost
     * special case.
     */
    public val isMonotone: Boolean
        get() = stages.none { it.coordinationKind == CoordinationKind.Coordinated }

    /**
     * The single [DraftStage.Embroider] stage in this pipeline, or `null` if none
     * was added.
     *
     * Per CALM, a well-formed [Draft] has at most one coordination step. The E-2
     * planner pattern-matches on this field to locate and defer the consensus
     * boundary.
     */
    public val embroidery: DraftStage.Embroider?
        get() = stages.filterIsInstance<DraftStage.Embroider>().singleOrNull()

    /**
     * Appends a [DraftStage.Map] stage referencing [opId] and returns a new [Draft].
     *
     * The referenced op is assumed to be a monotone function — one that preserves
     * the lattice ordering. The stage is tagged [CoordinationKind.Free].
     *
     * No op is invoked. Only the name is recorded.
     */
    public fun map(opId: OpId): Draft<ByteArray> = Draft(stages + DraftStage.Map(opId))

    /**
     * Appends a [DraftStage.Filter] stage referencing [opId] and returns a new [Draft].
     *
     * The referenced op is assumed to be a monotone predicate. The stage is tagged
     * [CoordinationKind.Free].
     *
     * No op is invoked. Only the name is recorded.
     */
    public fun filter(opId: OpId): Draft<T> = Draft(stages + DraftStage.Filter(opId))

    /**
     * Appends a [DraftStage.Embroider] stage referencing [opId] and returns a new [Draft].
     *
     * The embroider stage is the single coordination step — tagged
     * [CoordinationKind.Coordinated]. Place it last (or let the E-2 planner defer it
     * there automatically) to minimise consensus rounds.
     *
     * No op is invoked. Only the name is recorded.
     */
    public fun embroider(opId: OpId): Draft<T> = Draft(stages + DraftStage.Embroider(opId))
}
