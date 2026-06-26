package us.tractat.kuilt.warp

/**
 * Batched-execution path for [Draft] pipelines (G5).
 *
 * Walks the coordinated nodes of a [Draft] in topological order and issues one call to
 * [propose] per node. A [DraftStage.BatchedEmbroider] — the product of G3 consolidation
 * carries multiple agreements in a single proposal, so it costs **one call**, not one per op.
 *
 * ## Round-count reduction at execution
 *
 * For an unplanned draft with K independent [DraftStage.Embroider] nodes, [executeCoordinated]
 * issues K proposals. For the same draft after [Draft.plan] — which fuses independent embroiders
 * at the same dependency level into [DraftStage.BatchedEmbroider] nodes — it issues only
 * `depth` proposals, where `depth` is the number of dependency levels in the coordination DAG.
 *
 * This matches the analytical [CoordinationCost.rounds] model:
 * ```
 * roundsAtExecution == coordinationCost(plan(draft), stats).rounds
 * ```
 *
 * ## Payload encoding
 *
 * Each proposal carries the embroider opId(s) of its node as a comma-separated UTF-8
 * string — the symbolic agreements that ride that Raft round-trip. A
 * [DraftStage.BatchedEmbroider] encodes all its opIds in one payload; a lone
 * [DraftStage.Embroider] encodes one opId. Callers interpret the bytes; this function
 * only produces them.
 *
 * ## Ordering and dependencies
 *
 * Nodes are visited in topological order (the order they appear in [Draft.nodes]). A
 * sequential dependency (level N depends on level N−1) is automatically enforced because
 * [propose] suspends until the proposal commits before the next proposal is issued.
 *
 * ## Fully-monotone drafts
 *
 * When [Draft.isMonotone] is `true`, no coordinated node exists and [executeCoordinated]
 * issues zero proposals and returns 0 immediately.
 *
 * @param propose A suspending function that submits the proposal bytes to the consensus
 *   system (e.g. `sim.proposeOnLeader(it)`) and suspends until the proposal commits.
 * @return The count of proposals issued — equal to the number of coordinated nodes in this
 *   draft, and equal to [coordinationCost]`.rounds` on a planned draft.
 *
 * @sample us.tractat.kuilt.warp.sampleExecuteCoordinated
 * @see Draft.plan
 * @see coordinationCost
 * @see CoordinationCost.rounds
 */
public suspend fun Draft<*>.executeCoordinated(propose: suspend (ByteArray) -> Unit): Int {
    val coordinatedNodes = nodes.filter { it.stage.coordinationKind == CoordinationKind.Coordinated }
    for (node in coordinatedNodes) {
        propose(node.proposalBytes())
    }
    return coordinatedNodes.size
}

/** Encodes the coordinated opIds of this node as a comma-separated UTF-8 payload. */
private fun DraftNode.proposalBytes(): ByteArray =
    stage.coordinatedOpIds().joinToString(",") { it.value }.encodeToByteArray()
