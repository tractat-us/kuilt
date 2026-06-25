package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId

/**
 * A distributed results board — the dedup backstop for `:kuilt-warp`.
 *
 * Backed by an `ORMap<TaskId, LWWRegister<Result>>`: each task ID maps to a
 * last-writer-wins register holding the task's result. A duplicate execution of the
 * same task (same `taskId`) is **absorbed idempotently**: the [LWWRegister] join picks
 * the result tagged with the highest `(timestamp, replicaId)`, discarding the other.
 * The result map always converges to exactly one result per task.
 *
 * **Merge semantics — why LWW and not MVRegister?**
 * `Results` tolerates duplicate execution (the warp design expects ~5–25% duplication
 * under partition; see `docs/warp-foundation.md`). The goal is a single converged result
 * per task, not a set of concurrent values. LWW achieves this with zero extra state: both
 * executions produce a result; the one with the higher timestamp wins; the other is
 * silently discarded. For embarrassingly-parallel tasks the discarded result is never
 * wrong — it is a redundant computation on identical input. Callers that cannot tolerate
 * even silent discard should use the Coordinated path (slice B).
 *
 * **Clock responsibility.** The `timestamp` passed to [record] must be monotonically
 * increasing *within a single replica* to prevent older writes silently winning. Wall-clock
 * milliseconds are the common case; a logical clock or HLC is safer under skewed clocks.
 *
 * Immutable: [record] returns a new instance. [merge] is the causal join.
 *
 * @param TaskId the type used to identify tasks — must be a stable, unique, serializable key.
 * @param Result the result type produced by executing a task.
 */
public class Results<TaskId, Result> private constructor(
    private val map: ORMap<TaskId, LWWRegister<Result>>,
) {

    /** The set of task IDs for which a result has been recorded. */
    public val taskIds: Set<TaskId> get() = map.keys

    /**
     * The recorded result for [taskId], or `null` if no result has been recorded yet.
     */
    public operator fun get(taskId: TaskId): Result? = map[taskId]?.value

    /**
     * Record a [result] for [taskId] on behalf of [replica] at [timestamp].
     *
     * If a result for [taskId] already exists locally, the [LWWRegister] join selects
     * whichever has the higher `(timestamp, replicaId)` tag. A duplicate execution's
     * result is absorbed — neither value is lost before merge; convergence picks one.
     *
     * **Precondition — tag uniqueness.** The `(replica, timestamp)` pair must uniquely
     * identify this write. Never reuse the same `(replica, timestamp)` for a different
     * result — see [LWWRegister.set] for the contract.
     */
    public fun record(
        replica: ReplicaId,
        taskId: TaskId,
        timestamp: Long,
        result: Result,
    ): Results<TaskId, Result> {
        val register = map[taskId]?.set(replica, timestamp, result)
            ?: LWWRegister.empty<Result>().set(replica, timestamp, result)
        return Results(map.put(replica, taskId, register))
    }

    /**
     * The causal merge of two replicas of this results board.
     *
     * Idempotent, commutative, and associative — safe to call in any order and any
     * number of times. A task ID present on both sides is merged via [LWWRegister.piece],
     * so the higher-tagged result wins.
     */
    public fun merge(other: Results<TaskId, Result>): Results<TaskId, Result> =
        Results(map.piece(other.map))

    override fun equals(other: Any?): Boolean =
        other is Results<*, *> && map == other.map

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = "Results(taskIds=$taskIds)"

    public companion object {
        /** An empty results board with no recorded results. */
        public fun <TaskId, Result> empty(): Results<TaskId, Result> =
            Results(ORMap.empty())

        /** Wrap a raw [ORMap] as a [Results] — internal use by [WarpNode]. */
        internal fun <TaskId, Result> from(map: ORMap<TaskId, LWWRegister<Result>>): Results<TaskId, Result> =
            Results(map)
    }
}
