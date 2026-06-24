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
 * Apply a coordinated task with [commit], which executes your escalation logic
 * and produces a result. In production, [commit] will delegate to the Raft-backed
 * proposal path; in this slice it is a pure function accepting a transform so the
 * seam can be tested without a Raft cluster.
 *
 * @param A the value type — no monotone constraint required.
 * @property value the value to be processed under coordination.
 * @see CoordinationFree
 */
public class Coordinated<A>(public val value: A) {

    /**
     * Apply [transform] to [value] under the coordination contract and return the
     * result.
     *
     * The name "commit" matches the warp vocabulary: coordination-free contributions
     * are *embroidered* (merged); coordinated ones are *committed* (finalized via
     * consensus). In a full WarpNode the transform body would propose to the Raft
     * log; here it is a pure function so the seam is testable standalone.
     *
     * @param R the result type.
     * @param transform the action to run on [value] under the coordination guarantee.
     */
    public fun <R> commit(transform: (A) -> R): R = transform(value)

    override fun equals(other: Any?): Boolean =
        other is Coordinated<*> && value == other.value

    override fun hashCode(): Int = value?.hashCode() ?: 0

    override fun toString(): String = "Coordinated($value)"
}
