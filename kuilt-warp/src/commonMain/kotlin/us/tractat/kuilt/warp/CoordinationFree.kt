package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.Quilted

/**
 * A task or contribution that is safe to apply without coordination — it lives in a
 * join-semilattice, so concurrent applications from any number of peers converge to
 * the same result regardless of order, duplication, or delivery gaps.
 *
 * The monotone contract is encoded in the type: only values that implement [Quilted]
 * (i.e., values whose [Quilted.piece] join is idempotent, commutative, and associative)
 * can be wrapped here. This makes it structurally impossible to place a non-monotone
 * value on the coordination-free path.
 *
 * Use [embroider] to merge a contribution into this value. For tasks that are NOT
 * idempotent or require strict exactly-once delivery, use [Coordinated] instead.
 *
 * @param A the lattice type — must implement [Quilted].
 * @property state the current lattice value.
 * @see Coordinated
 * @see embroider
 * @see joinAll
 */
public class CoordinationFree<A : Quilted<A>>(public val state: A) {

    /**
     * Apply [contribution] by joining its state into this value's state.
     *
     * This is the warp verb for the coordination-free path: a monotone "apply" that
     * absorbs a peer's contribution without any round-trip or lock. The join satisfies
     * the lattice laws (idempotent, commutative, associative), so the result is the
     * same no matter how many times or in what order [embroider] is called with the
     * same contributions.
     */
    public fun embroider(contribution: CoordinationFree<A>): CoordinationFree<A> =
        CoordinationFree(state.piece(contribution.state))

    override fun equals(other: Any?): Boolean =
        other is CoordinationFree<*> && state == other.state

    override fun hashCode(): Int = state.hashCode()

    override fun toString(): String = "CoordinationFree($state)"
}

/**
 * Apply [transform] to this value's state, producing a new [CoordinationFree] with
 * the transformed state. The caller is responsible for ensuring [transform] is itself
 * monotone — i.e. that the output lattice's join is preserved under the mapping.
 *
 * This is a B3 combinator: it composes coordination-free values by mapping their states
 * through a monotone function. It cannot be called on a [Coordinated] value.
 *
 * @param B the output lattice type.
 * @param transform a monotone function from [A] to [B].
 */
public fun <A : Quilted<A>, B : Quilted<B>> CoordinationFree<A>.monotoneMap(
    transform: (A) -> B,
): CoordinationFree<B> = CoordinationFree(transform(state))

/**
 * Lift a raw [Quilted] value into the coordination-free path.
 *
 * Convenience B3 factory: equivalent to `CoordinationFree(value)` but reads as an
 * explicit decision — "I know this value is monotone; I am opting into the fast path."
 */
public fun <A : Quilted<A>> liftCoordinationFree(value: A): CoordinationFree<A> =
    CoordinationFree(value)

/**
 * Compute the join (least upper bound) of all values in [contributions].
 *
 * This is the B3 merge-all combinator. The result is the same as folding [embroider]
 * left across the list. The list must not be empty.
 *
 * @throws NoSuchElementException if [contributions] is empty.
 */
public fun <A : Quilted<A>> joinAll(contributions: List<CoordinationFree<A>>): CoordinationFree<A> =
    contributions.reduce { acc, next -> acc.embroider(next) }
