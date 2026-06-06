package us.tractat.kuilt.crdt

import kotlin.jvm.JvmInline

/**
 * A delta-state CRDT: a value living in a join-semilattice.
 *
 * [piece] is the join — the least-upper-bound of two states. It MUST satisfy
 * the three lattice laws:
 *
 *  - **idempotent**   `a.piece(a) == a`
 *  - **commutative**  `a.piece(b) == b.piece(a)`
 *  - **associative**  `a.piece(b).piece(c) == a.piece(b.piece(c))`
 *
 * These laws are exactly what make convergence robust to kuilt's frame delivery
 * semantics: a fabric may drop, duplicate, and reorder frames, but any two
 * replicas that have absorbed the same *set* of states — in any order, with any
 * repeats — compute the same value.
 *
 * Operations are modeled as delta-mutators that return a [Patch] (a small
 * fragment of the same lattice), which any replica absorbs with [piece]. The
 * name nods to kuilt's quilting metaphor: a whole pieced from independent
 * patches.
 *
 * @param S the self-type — implementors write `class Foo : Quilted<Foo>`.
 */
public interface Quilted<S : Quilted<S>> {
    /** The join: the least-upper-bound of `this` and [other]. */
    public fun piece(other: S): S
}

/**
 * A delta produced by a mutator: a small element of the same lattice as [S].
 * A delta *is* a state fragment, so it is absorbed by the very same
 * [Quilted.piece] join — see [piece].
 */
@JvmInline
public value class Patch<S : Quilted<S>>(public val delta: S)

/** Absorb a [patch] into this state via [Quilted.piece]. */
public fun <S : Quilted<S>> S.piece(patch: Patch<S>): S = piece(patch.delta)
