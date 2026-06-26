package us.tractat.kuilt.warp

import kotlin.jvm.JvmInline

/**
 * An opaque node identifier in a [Draft] dependency DAG.
 *
 * Each [DraftNode] in a [Draft] carries a unique [NodeId] assigned at construction
 * time. Predecessor edges ([DraftNode.predecessors]) reference nodes by their [NodeId],
 * forming the directed acyclic graph that encodes computation dependencies.
 *
 * In the path-preserving builder ([Warp.shuttle] / [Draft.map] / [Draft.filter] /
 * [Draft.embroider]), ids are assigned sequentially (0, 1, 2, …); a linear pipeline is
 * the degenerate path — each node has exactly one predecessor. The G2 `combine`
 * combinator will introduce true branching with multiple predecessors.
 *
 * @see DraftNode
 * @see Draft
 */
@JvmInline
public value class NodeId(public val value: Int)

/**
 * One node in a [Draft] dependency DAG — a [DraftStage] plus its predecessor edges.
 *
 * The pair ([id], [predecessors]) encodes the graph structure: a node depends on all
 * the nodes in [predecessors] and must run after they do. [predecessors] is empty only
 * for the root node (a [DraftStage.Source]).
 *
 * On a path (the degenerate case produced by the path-preserving builder),
 * every node has exactly one predecessor except the source — this is structurally
 * equivalent to the previous `List<DraftStage>` representation and produces the same
 * topological order.
 *
 * @see NodeId
 * @see Draft
 */
public data class DraftNode(
    /** This node's unique identity within its [Draft]. */
    public val id: NodeId,
    /** The operation this node performs. */
    public val stage: DraftStage,
    /** The set of nodes that must complete before this node can run. Empty for root nodes. */
    public val predecessors: Set<NodeId>,
)

/**
 * An immutable, inspectable dataflow graph that captures a distributed computation
 * without running it.
 *
 * Think of a [Draft] as a recipe, not a meal. It records *what* should happen — a
 * dependency graph of named operations — but touches no data and invokes no code. This
 * lets a planner inspect, rewrite, and optimise the graph before committing to execution
 * (E-2 rewrites, E-3 cost model, E-5 incremental execution, G2–G5 DAG consolidation).
 *
 * ## Structure — dependency DAG
 *
 * A [Draft] is a directed acyclic graph of [DraftNode]s. Each node wraps a [DraftStage]
 * and carries the set of [NodeId]s it depends on ([DraftNode.predecessors]). A linear
 * pipeline — built by chaining [map]/[filter]/[embroider] — is the degenerate **path**:
 * each node has exactly one predecessor.
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
 * - [nodes] — all [DraftNode]s in topological order, with predecessor edges.
 * - [stages] — topological-order view of stage types; backward-compatible with E-5
 *   consumers ([ConvergentExecution]) that iterate the stage sequence.
 * - [isMonotone] — `true` when every stage is [CoordinationKind.Free]; no consensus
 *   needed. The planner can run the whole pipeline coordination-free.
 * - [embroideries] — all [DraftStage.Embroider] nodes in topological order.
 *   A path has at most one; G2 `combine` can introduce more.
 * - [embroidery] — convenience accessor for the single [DraftStage.Embroider], or
 *   `null` if none. Equivalent to `embroideries.singleOrNull()`.
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
 * @sample us.tractat.kuilt.warp.sampleShuttle
 * @see Warp.shuttle
 * @see DraftNode
 * @see DraftStage
 */
public class Draft<out T> @PublishedApi internal constructor(
    /** All nodes of this dependency DAG in topological order (dependencies precede dependents). */
    public val nodes: List<DraftNode>,
) {

    /**
     * Topological-order view of the stage types recorded in this [Draft].
     *
     * Equivalent to `nodes.map { it.stage }`. Backward-compatible with E-5 consumers
     * ([ConvergentExecution]) that iterate the stage sequence.
     */
    public val stages: List<DraftStage>
        get() = nodes.map { it.stage }

    /**
     * `true` when every stage in this [Draft] is [CoordinationKind.Free] — no
     * consensus step is present. A monotone pipeline converges without any Raft round.
     *
     * The E-3 cost model counts coordination stages; [isMonotone] is the zero-cost
     * special case.
     */
    public val isMonotone: Boolean
        get() = nodes.none { it.stage.coordinationKind == CoordinationKind.Coordinated }

    /**
     * All [DraftStage.Embroider] nodes in this [Draft], in topological order.
     *
     * A path-only [Draft] (the degenerate case built by [Warp.shuttle]) has at most
     * one embroider. The G2 `combine` combinator can produce [Draft]s with multiple
     * independent embroider branches; they all appear here.
     *
     * Use [embroidery] when you need the single embroider of a path and want to assert
     * the single-embroider invariant.
     */
    public val embroideries: List<DraftStage.Embroider>
        get() = nodes.mapNotNull { it.stage as? DraftStage.Embroider }

    /**
     * The single [DraftStage.Embroider] stage in this pipeline, or `null` if none
     * was added. Equivalent to `embroideries.singleOrNull()`.
     *
     * Per CALM, a well-formed path [Draft] has at most one coordination step. The E-2
     * planner pattern-matches on this field to locate and defer the consensus boundary.
     * For [Draft]s with multiple embroiders (G2+), use [embroideries] directly.
     */
    public val embroidery: DraftStage.Embroider?
        get() = embroideries.singleOrNull()

    /**
     * Appends a [DraftStage.Map] stage referencing [opId] and returns a new [Draft].
     *
     * The referenced op is assumed to be a monotone function — one that preserves
     * the lattice ordering. The stage is tagged [CoordinationKind.Free].
     *
     * Produces a path: the new node's sole predecessor is the current tip.
     * No op is invoked. Only the name is recorded.
     */
    public fun map(opId: OpId): Draft<ByteArray> = appendPathNode(DraftStage.Map(opId))

    /**
     * Appends a [DraftStage.Filter] stage referencing [opId] and returns a new [Draft].
     *
     * The referenced op is assumed to be a monotone predicate. The stage is tagged
     * [CoordinationKind.Free].
     *
     * Produces a path: the new node's sole predecessor is the current tip.
     * No op is invoked. Only the name is recorded.
     */
    public fun filter(opId: OpId): Draft<T> = appendPathNode(DraftStage.Filter(opId))

    /**
     * Appends a [DraftStage.Embroider] stage referencing [opId] and returns a new [Draft].
     *
     * The embroider stage is the single coordination step — tagged
     * [CoordinationKind.Coordinated]. Place it last (or let the E-2 planner defer it
     * there automatically) to minimise consensus rounds.
     *
     * Produces a path: the new node's sole predecessor is the current tip.
     * No op is invoked. Only the name is recorded.
     */
    public fun embroider(opId: OpId): Draft<T> = appendPathNode(DraftStage.Embroider(opId))

    private fun <R> appendPathNode(stage: DraftStage): Draft<R> {
        val newId = NodeId(nodes.size)
        val predecessors = nodes.lastOrNull()?.let { setOf(it.id) } ?: emptySet()
        val newNodes = nodes + DraftNode(id = newId, stage = stage, predecessors = predecessors)
        @Suppress("UNCHECKED_CAST")
        return Draft<Any>(newNodes) as Draft<R>
    }
}

// ── Internal graph helpers ────────────────────────────────────────────────────

/**
 * Builds a path-predecessor [DraftNode] list from an ordered stage list.
 *
 * Each stage becomes a node with [NodeId] equal to its list index; each node's sole
 * predecessor is the node at `index - 1`. The source node (index 0) has no predecessors.
 *
 * Used by the backward-compatible `List<DraftStage>` constructor and by rewrites that
 * produce reordered stage lists and need to rebuild the node graph.
 */
internal fun List<DraftStage>.toPathNodes(): List<DraftNode> =
    mapIndexed { index, stage ->
        DraftNode(
            id = NodeId(index),
            stage = stage,
            predecessors = if (index == 0) emptySet() else setOf(NodeId(index - 1)),
        )
    }

/**
 * Computes the set of successor [NodeId]s for every node in this list.
 *
 * A node S is a successor of P if P's id appears in S's predecessors.
 * Used by rewrites to navigate forward in the DAG (e.g. to check whether an Embroider
 * node has any Free successors before deferring it).
 */
internal fun List<DraftNode>.successors(): Map<NodeId, Set<NodeId>> {
    val result = mutableMapOf<NodeId, MutableSet<NodeId>>()
    for (node in this) {
        for (predId in node.predecessors) {
            result.getOrPut(predId) { mutableSetOf() }.add(node.id)
        }
    }
    return result
}
