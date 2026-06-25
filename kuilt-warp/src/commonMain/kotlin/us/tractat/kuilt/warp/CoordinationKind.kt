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
 * - [Coordinated] — the Raft-backed escalation path. The ring owner proposes the task
 *   to the `raftNode` supplied at [WarpNode] construction time, suspends until a quorum
 *   commits it, then calls `coordinatedExecutor` exactly once. Requires a non-null
 *   `raftNode`; [WarpNode.enqueue] throws [IllegalStateException] immediately if none
 *   was provided. A caller opts in by calling
 *   `enqueue(taskId, CoordinationKind.Coordinated)` and supplying a `raftNode` and
 *   `coordinatedExecutor` to [WarpNode].
 *
 * @see WarpNode.enqueue
 */
public sealed class CoordinationKind {

    /** Optimistic ring path — safe for idempotent tasks. The [WarpNode.enqueue] default. */
    public data object Free : CoordinationKind()

    /**
     * Raft-backed escalation path — for tasks that require a globally-agreed ordering or
     * exactly-once semantics. The ring owner proposes the task to the [WarpNode]'s `raftNode`
     * and calls `coordinatedExecutor` only after a quorum commits the log entry. Requires
     * `raftNode` to be non-null in the [WarpNode] constructor.
     */
    public data object Coordinated : CoordinationKind()
}
