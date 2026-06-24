package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId

/**
 * A distributed set of pending task IDs — the task-distribution primitive for `:kuilt-warp`.
 *
 * Backed by an [ORSet], so concurrent adds from multiple peers merge correctly (add-wins),
 * and a task removed by one peer while concurrently re-added by another still survives.
 * Tasks added during a network partition are preserved and merged on reconnect.
 *
 * **Ring-agnostic.** `WorkQueue` holds *all* pending tasks; filtering to the tasks owned
 * by the local peer (`owner(task) == self`) is done externally by the `WarpNode` via the
 * `TaskRing`. This keeps the CRDT layer clean and makes the queue independently testable.
 *
 * Immutable: [add] and [remove] return a new instance. [merge] is the causal join.
 *
 * @param TaskId the type used to identify tasks — must be a stable, unique, serializable key.
 */
public class WorkQueue<TaskId> private constructor(
    private val set: ORSet<TaskId>,
) {

    /** The set of task IDs currently pending. */
    public val pending: Set<TaskId> get() = set.elements

    /**
     * Add [taskId] to the pending set on behalf of [replica].
     *
     * A fresh causal dot is minted, so this add survives a concurrent remove of the same
     * task ID on a peer that hasn't seen this add yet (add-wins semantics).
     */
    public fun add(replica: ReplicaId, taskId: TaskId): WorkQueue<TaskId> =
        WorkQueue(set.add(replica, taskId))

    /**
     * Remove [taskId] from the pending set.
     *
     * Only the dots currently on this task ID are cancelled; a concurrent add on another
     * replica that minted a new dot survives the merge (observed-remove semantics).
     */
    public fun remove(taskId: TaskId): WorkQueue<TaskId> =
        WorkQueue(set.remove(taskId))

    /**
     * The causal merge of two replicas of this queue.
     *
     * Idempotent, commutative, and associative — safe to call in any order and any
     * number of times.
     */
    public fun merge(other: WorkQueue<TaskId>): WorkQueue<TaskId> =
        WorkQueue(set.piece(other.set))

    override fun equals(other: Any?): Boolean =
        other is WorkQueue<*> && set == other.set

    override fun hashCode(): Int = set.hashCode()

    override fun toString(): String = "WorkQueue(pending=$pending)"

    public companion object {
        /** An empty work queue with no pending tasks. */
        public fun <TaskId> empty(): WorkQueue<TaskId> = WorkQueue(ORSet.empty())
    }
}
