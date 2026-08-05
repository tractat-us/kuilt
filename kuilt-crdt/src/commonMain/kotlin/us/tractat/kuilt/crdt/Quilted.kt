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
 * **Two mutator shapes, one absorption path.** A mutator may return either a
 * [Patch] (the minimal lattice fragment for the change — [BoundedCounter.trySpend],
 * `ORSet.add`, `ORMap.put`, `LWWMap.set`) or a full new state `S` ([LWWRegister.set],
 * [Gauge.observe]). Both are absorbed by the identical [piece] join: a `Patch` is
 * applied with `piece(patch)`, and a returned full state is itself a valid join
 * argument (`a.piece(newState)`).
 *
 * **Return a full state only when the whole state genuinely *is* the minimal
 * delta.** That is true of a single-cell type — an [LWWRegister] holds one tagged
 * value, so there is nothing smaller to send. It is **not** true of a collection,
 * however single-celled its per-key merge looks: an `ORSet` add touches one
 * element, an `ORMap` put one key, an `LWWMap` set one cell, and returning the
 * container would put every *other* element on the wire too. Those three return a
 * [Patch] for exactly that reason, and their deltas are pinned byte-for-byte by
 * the delta-mutator law `X.piece(mᵟ(X)) == m(X)` (#2044).
 *
 * An earlier version of this paragraph offered *registers and maps* together as
 * the family whose whole state is already minimal. The maps were never in it, and
 * that sentence is a large part of why every write shipped O(state) bytes for six
 * months without anyone looking. When adding a type here, ask what one write
 * costs on a large instance — not what the merge function looks like.
 *
 * @param S the self-type — implementors write `class Foo : Quilted<Foo>`.
 */
public interface Quilted<S : Quilted<S>> {
    /** The join: the least-upper-bound of `this` and [other]. */
    public fun piece(other: S): S

    /**
     * The causal [Dot]s this state has delivered — `(author, author-seq)` per op.
     *
     * This is the capability the causal-stability GC of ADR-003 addendum v3 (#262)
     * needs without breaking [Quilted]'s genericity: a [Quilter] generic over
     * `Quilted<S>` cannot know about any one CRDT's internal op identities, so the
     * CRDT exposes them here. The replicator folds these dots into a contiguous
     * **delivered** [VersionVector] (highest gap-free seq per author) and gossips it.
     *
     * Only op-based CRDTs whose elements carry per-author dense [Dot]s participate —
     * today that is [Rga], which returns its `Insert`/`Remove` op dots and **excludes**
     * `Compact` ops (a compaction mints no author dot). Every other delta-state CRDT in
     * the zoo (`GCounter`, `ORSet`, …) does not use this GC path; the default empty set
     * keeps the capability non-breaking for them — they contribute nothing to any
     * delivered vector.
     */
    public fun causalDots(): Set<Dot> = emptySet()
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

/**
 * This state with the delta [mutate] produces from it absorbed — the delta-mutator law
 * `X.piece(mᵟ(X))` spelled once, so a caller need not name `X` twice.
 *
 * Reach for it when you hold a CRDT **outside** a [us.tractat.kuilt.quilter.Quilter] and want
 * the resulting whole state rather than a frame to broadcast:
 *
 * ```kotlin
 * val roster = ORSet.empty<String>()
 *     .piece { it.add(alpha, "ada") }
 *     .piece { it.add(bravo, "grace") }
 * ```
 *
 * Under a replicator, do **not** use this — `quilter.mutate { it.add(replica, element) }` takes
 * the same lambda, applies it under the replicator's own lock, and broadcasts only the delta.
 * Absorbing locally and handing the replicator the whole state is the O(state) mistake every
 * mutator in this package now returns a [Patch] to prevent.
 */
public inline fun <S : Quilted<S>> S.piece(mutate: (S) -> Patch<S>): S = piece(mutate(this))
