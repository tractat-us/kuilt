package us.tractat.kuilt.warp

/**
 * Entry point for the `:kuilt-warp` module — distributed task scheduling over a connected mesh.
 *
 * Warp spreads a pile of work across whoever is connected, with no central boss and no peer
 * doing the same job twice. See `module.md` for the full design walk and
 * `docs/warp-foundation.md` for the architectural detail.
 *
 * The implementation types are `TaskRing`, `WorkQueue`, and `WarpNode`. See those types
 * for per-component detail and `module.md` for the design walk across both execution paths.
 *
 * ## Draft builder
 *
 * Use [shuttle] to begin building a [Draft] — an immutable, inspectable dataflow graph
 * that records a distributed computation without running it:
 *
 * ```kotlin
 * val draft: Draft<ByteArray> = Warp.shuttle(OpId("docs"))
 *     .map(OpId("score"))
 *     .filter(OpId("above-threshold"))
 *     .embroider(OpId("rank"))
 * ```
 *
 * The returned [Draft] stores only symbolic [OpId]s. Nothing executes until E-5
 * provides a runtime.
 */
public object Warp {

    /**
     * Creates a new [Draft] whose first stage is a [DraftStage.Source] identified
     * by [sourceOpId].
     *
     * The source stage represents named data living on peers — a collection, a
     * named view, or any operation that produces elements for downstream stages.
     * Chain [Draft.map], [Draft.filter], and [Draft.embroider] on the returned
     * [Draft] to build up the full pipeline.
     *
     * No op is invoked. Only the name is recorded.
     *
     * @param sourceOpId the symbolic name of the source operation.
     * @return a single-stage [Draft] ready to extend with transforms.
     */
    public fun shuttle(sourceOpId: OpId): Draft<ByteArray> =
        Draft(listOf(DraftNode(id = NodeId(0), stage = DraftStage.Source(sourceOpId), predecessors = emptySet())))
}
