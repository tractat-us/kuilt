package us.tractat.kuilt.warp

/**
 * A task that requires coordination — it is not safe to apply without a consensus
 * round-trip. This is the escalation path: tasks that are non-idempotent, require
 * strict exactly-once delivery, or whose correctness depends on a globally-agreed
 * ordering must be placed here.
 *
 * The type has no [Quilted] bound: it explicitly allows non-monotone values. A
 * [Coordinated] value cannot be passed to [embroider] or [joinAll] — those
 * combinators accept only [CoordinationFree]. This makes the type boundary a
 * compile-time gate: a caller who picks [Coordinated] is automatically routed
 * toward the consensus path in `WarpNode` (#813).
 *
 * Apply a coordinated task with [commit], which applies your transform to the wrapped
 * [value]. In [WarpNode], coordinated tasks are backed by a Raft cluster (supplied as
 * the `raftNode` constructor parameter) that achieves exactly-once execution in
 * the common case and under tested roster-churn scenarios. Two residual timing
 * windows can cause duplicate execution or task stranding in rare cases (see #879).
 * The [Results] ORMap backstop absorbs duplicate results from the dual-leader window.
 *
 * @param A the value type — no monotone constraint required.
 * @property value the value to be processed under coordination.
 * @see CoordinationFree
 */
public class Coordinated<A>(public val value: A) {

    /**
     * Apply [transform] to [value] and return the result.
     *
     * The name "commit" matches the warp vocabulary: coordination-free contributions
     * are *embroidered* (merged); coordinated ones are *committed* (finalised via
     * consensus). The exactly-once guarantee is enforced by [WarpNode]'s Raft-backed
     * proposal path — not by this method, which applies [transform] locally and is
     * useful for constructing the value before handing it to [WarpNode.enqueue].
     *
     * @param R the result type.
     * @param transform the action to run on [value].
     */
    public fun <R> commit(transform: (A) -> R): R = transform(value)

    override fun equals(other: Any?): Boolean =
        other is Coordinated<*> && value == other.value

    override fun hashCode(): Int = value?.hashCode() ?: 0

    override fun toString(): String = "Coordinated($value)"
}
