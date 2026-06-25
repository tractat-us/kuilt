package us.tractat.kuilt.warp

/**
 * Tags each task in the warp queue as either coordination-free or coordinated.
 *
 * The tag is set at [WarpNode.enqueue] time and determines which execution path
 * [WarpNode] dispatches the task to:
 *
 * - [Free] — the optimistic consistent-hash ring path. Execution is idempotent; the
 *   [Results] ORMap backstop absorbs any duplicate executions that arise during failover.
 *   This is the default path when [WarpNode.enqueue] is called without a kind argument.
 *
 * - [Coordinated] — the escalation path, routed to [WarpNode]'s `coordinatedExecutor`.
 *   Intended for tasks that are non-idempotent or require a globally-agreed ordering.
 *   In this slice the routing seam is established; the Raft-backed consensus wiring
 *   lands in B-2 (#859). A caller opts in by calling
 *   `enqueue(taskId, CoordinationKind.Coordinated)` and supplying a `coordinatedExecutor`
 *   to [WarpNode].
 *
 * @see WarpNode.enqueue
 */
public sealed class CoordinationKind {

    /** Optimistic ring path — safe for idempotent tasks. The [WarpNode.enqueue] default. */
    public data object Free : CoordinationKind()

    /**
     * Escalation path — for tasks that require a globally-agreed ordering or exactly-once
     * semantics. Routes to [WarpNode]'s `coordinatedExecutor`. Consensus wiring (Raft) is
     * the B-2 concern; this kind establishes the routing seam.
     */
    public data object Coordinated : CoordinationKind()
}
