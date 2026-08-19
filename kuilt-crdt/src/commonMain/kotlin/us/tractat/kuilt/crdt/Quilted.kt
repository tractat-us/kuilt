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
 * element, an `ORMap` put one key, an `LWWMap` set one cell, a `JsonCrdt` set one
 * document key, and returning the container would put every *other* element on
 * the wire too. Those four return a [Patch] for exactly that reason, and their
 * deltas are pinned byte-for-byte by the delta-mutator law `X.piece(mᵟ(X)) == m(X)`
 * (#2044, #2111) — unconditionally for `ORSet`, `ORMap` and `JsonCrdt`, and for
 * `LWWMap` exactly while the write's `(timestamp, replica)` tag dominates the
 * key's current one, which is what its own tag-uniqueness rule already requires.
 * Outside that domain no delta exists, because a delta is joined and a join can
 * only move up the lattice (#2087).
 *
 * **The law is necessary and nowhere near sufficient, so do not stop at it.** A
 * whole state dominates itself, so `X.piece(whole(X)) == whole(X)` holds for any
 * lattice at all: a mutator returning `Patch(wholeNewState)` satisfies the law
 * perfectly while saving nothing. No property of [piece] can tell the two apart —
 * only measuring the encoded frame across two instance sizes can. Every type above
 * therefore carries a *flat-frame* test beside its law test, and a delta shape
 * that is merely correct is not the thing being claimed here.
 *
 * A pair of traps that both caught this codebase, worth reading before adding a
 * type. An earlier version of this paragraph offered *registers and maps* together
 * as the family whose whole state is already minimal; the maps were never in it,
 * and that sentence is a large part of why every write shipped O(state) bytes for
 * six months without anyone looking. And a law test is only as good as its
 * reference: `JsonCrdt`'s first one compared the delta path against
 * `root.piece { it.put(…) }`, which *is* `root.piece(root.put(…).delta)` — the same
 * expression — so it asserted `x == x` and pinned nothing (#2111). Point the
 * reference at a second implementation and prove it by mutating the delta path and
 * watching the law go red. When adding a type here, ask what one write costs on a
 * large instance — not what the merge function looks like.
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
     *
     * **This is only half the delivered surface.** A consumer folding a delivered frontier
     * must read `causalDots() ∪ {dots at-or-below causalFloor()}` — the union is the contract,
     * not a partition. The two halves are not guaranteed disjoint: [Rga.dropWindow]'s
     * contiguity walk can raise the floor past an own dot a still-retained `Compact` op
     * already recorded (stepping over an inherited or previously-explicit `Compact` so the
     * floor doesn't wedge below it), leaving that dot beneath the floor *and* still re-emitted
     * here. The overlap is harmless — every consumer only ever asks "was this dot delivered,"
     * never "which half reported it."
     */
    public fun causalDots(): Set<Dot> = emptySet()

    /**
     * The per-author high-water of dots this state **delivered and has since compacted away
     * without retaining their identities**.
     *
     * The bounded companion to [causalDots]. An op-log CRDT that garbage-collects must keep
     * its delivered frontier gap-free, or the author's high-water pins below the gap forever
     * and all downstream GC stalls. [Rga] keeps it gap-free across an explicit `RgaOp.Compact`
     * by re-emitting every id that op recorded through [causalDots] — correct, but the record
     * it re-emits from is Θ(elements ever), and the replicator recomputes the fold on **every**
     * state change. A high-water says the same thing about a whole compacted prefix in
     * O(authors), which is what lets a windowed log stop growing.
     *
     * So the two are read together: a dot is delivered if it is in [causalDots] **or** at or
     * below this floor — read the union, not a partition. The halves can overlap (see
     * [causalDots] for why); that overlap is harmless, since no consumer needs to know which
     * half reported a given dot.
     *
     * It can only describe a **downward-closed** compacted set. `Rga.dropWindow` guarantees
     * that by advancing the floor across a contiguous own-dot run only, recording every other
     * drop as an explicit `Compact` that [causalDots] still re-emits. A CRDT whose compaction
     * is not downward-closed must keep re-emitting through [causalDots] and leave this
     * defaulted.
     *
     * The default is empty, so every delta-state CRDT in the zoo is unaffected — they neither
     * compact nor participate in this GC path.
     *
     * **Aggregated across nesting, wherever [causalDots] is.** Every composite that unions the
     * dots beneath it raises its floor the same way, by elementwise max ([VersionVector.ceilWith])
     * over the same reachable set: [LatticeProduct] across its two components, and
     * `JsonNode.Object` / `JsonNode.Array` / `JsonCrdt` across every nested [Rga]. It stays empty
     * unless something beneath is floored — no shipped path floors a nested `Rga`, but
     * `Rga.dropWindow` is public, so a consumer can hand one to any of them. A composite left on
     * the default would then report a frontier missing exactly the dots [causalDots] can no
     * longer re-emit. A new composite that overrides [causalDots] must override this too.
     */
    public fun causalFloor(): VersionVector = VersionVector.EMPTY
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
