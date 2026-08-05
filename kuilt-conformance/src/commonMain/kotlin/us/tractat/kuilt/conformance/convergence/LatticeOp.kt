package us.tractat.kuilt.conformance.convergence

import kotlin.random.Random

/**
 * Whether an op **asserts** something new or **retires** something an earlier op asserted.
 *
 * **This cannot be computed; the binding has to declare it.** A removal is *more information*, so
 * `s → s.remove(k)` moves **up** the join-semilattice exactly as `s → s.add(k)` does, and
 * `s ⊔ s.remove(k) == s.remove(k)` holds. There is no expression in the
 * [us.tractat.kuilt.crdt.Quilted] algebra that separates the two.
 *
 * **The cheap proxy is a false detector in both directions.** The harness already holds the
 * serializer, so "did the encoding shrink?" is free — and wrong, measured over the live bindings as
 * they stood when the proxy was evaluated:
 *
 * | binding | byte-shrinking steps | did any of those steps retire? |
 * |---|---|---|
 * | `LWWRegisterConvergenceTest` | **62.1%** | **none of them** — its alphabet was one `set` op, and a shorter value string simply encodes shorter |
 * | `TwoPhaseSetConvergenceTest` | **0.0%** | every `remove` does — tombstones make the state grow |
 * | `RgaConvergenceTest` | **0.0%** | every `remove` does |
 * | `MovableTreeConvergenceTest` | **0.0%** | every `move` does |
 *
 * A proxy that reads 62.1% on an alphabet where **nothing** retires and 0.0% on three where every
 * retiring op does is not a weak signal, it is an inverted one. Hence the declaration.
 *
 * (The 62.1% row is the reason to state this carefully rather than by type name. `LWWRegister`
 * *does* retire — `unset` is a documented last-writer-wins tombstone, and
 * `LWWRegisterConvergenceTest` now declares an `unset-high` op as [RETIRE]. What the row measures is
 * the **alphabet** that produced it, a single `set`; the proxy called 62.1% of those steps
 * retirements and every one of them was an assignment. Read as a claim about the *type* the row
 * would now be false, and the number would be lost with it.)
 *
 * **Why it is worth declaring at all.** A generator that never retires cannot reach the bug class
 * this suite exists for, and no *computable* floor notices. Same broken `ORMap`, same harness,
 * differing only in whether the generator could remove:
 *
 * | arm | strict-ancestor pairs | concurrent pairs | non-trivial inner join | effective retires | violations |
 * |---|---|---|---|---|---|
 * | generator as bound | 30.2% | 39.7% | 34.6% | 9.3% | **500 / 45,797** |
 * | removes deleted | 28.4% | 43.2% | 39.3% | **0.0%** | **0 / 47,059** |
 *
 * Ancestry, concurrency and join-non-triviality — the three floors a reviewer reaches for, and the
 * three a generic `Quilted` can compute — are all *satisfied* by the arm that finds nothing.
 * Ancestry even goes slightly **up** when the removes are deleted. The retirement rate is the only
 * column that separates a searching generator from a vacuous one.
 */
public enum class OpKind {
    /** Adds an observation: a put, an add, an insert, an increment. */
    ASSERT,

    /**
     * Withdraws an observation an earlier op made: a remove, a departure, a re-parent.
     *
     * "Withdraws" is about the *observable value*, not the encoding — `MovableTree.move` retires the
     * node from its previous parent's children even though the move log only grows.
     */
    RETIRE,
}

/**
 * One named operation a binding can perform against its state.
 *
 * The alphabet is the binding's whole vocabulary: [CrdtConvergenceHarness] draws from it to build
 * the randomised causal pool, and [CrdtConvergenceHarness.criticalShapes] names its members to
 * construct the shapes that must be reached on every seed rather than on a lucky one. One alphabet
 * driving both is the point — a constructed shape and a random trajectory cannot drift into
 * describing different sets of operations.
 *
 * @param name the identifier a critical shape uses to name this op. Unique within an alphabet.
 * @param kind see [OpKind] — declared, because it is not computable.
 * @param apply produce the next state. [replicaIndex] is `0 until replicaCount`; derive whatever
 *   identity the type needs from it. [random] is the pool builder's stream — see the determinism
 *   note below.
 *
 * **An op named by a critical shape must be deterministic in its *target*.** A shape like
 * `put · remove · put` only means anything if all three touch the same key, so an op that draws its
 * key from [random] cannot appear in one. Declare a target-pinned variant for the shape and let the
 * roaming variant serve random exploration. This is not merely advice: the harness asserts every
 * step of every shape changed the state, so a shape whose `remove` wanders off the key its `put`
 * created fails rather than quietly becoming decoration.
 */
public class LatticeOp<S>(
    public val name: String,
    public val kind: OpKind,
    public val apply: (state: S, replicaIndex: Int, random: Random) -> S,
) {
    override fun toString(): String = "$name(${kind.name.lowercase()})"
}

/**
 * The shape every retiring type must reach: **assert, retire, re-assert.**
 *
 * Returns `[[assertA, retire, assertB]]` for an alphabet with at least one [OpKind.RETIRE] op, and
 * an empty list for a grow-only one. `assertA` and `assertB` are the first two [OpKind.ASSERT] ops
 * in declaration order — **so declaration order is load-bearing**, and a binding that needs a
 * particular word should pass `criticalShapes` explicitly rather than rely on this.
 *
 * **Why re-assert with a *second* op.** A lattice that wrongly keeps a retired contribution and one
 * that correctly drops it can land on the identical state, and then the shape asserts nothing. The
 * discriminator is not that the re-asserted value *differs* — it is that it must **not dominate**
 * the retired one. Measured on `ORMap<String, GCounter>`: re-asserting a *larger* count under the
 * same author finds **0** violations against a lattice provably broken in exactly this way, because
 * a `GCounter` join takes the max per author and both branches land on the larger number. Re-assert
 * `1` after retiring `4` and the same construction finds the defect on **every** seed. When the
 * alphabet offers only one assert, the shape repeats it — still worth running, but its power then
 * rests on the op minting fresh identity (a new dot) rather than on domination.
 */
public fun <S> defaultCriticalShapes(alphabet: List<LatticeOp<S>>): List<List<String>> {
    val retire = alphabet.firstOrNull { it.kind == OpKind.RETIRE } ?: return emptyList()
    val asserts = alphabet.filter { it.kind == OpKind.ASSERT }
    if (asserts.isEmpty()) return emptyList()
    val assertA = asserts[0].name
    val assertB = (asserts.getOrNull(1) ?: asserts[0]).name
    return listOf(listOf(assertA, retire.name, assertB))
}

/**
 * How much *searching* a binding's generator has to do before its green counts for anything.
 *
 * A lattice law is a statement about a set of states, so a green run says exactly as much as the
 * pool it ran over. [CrdtConvergenceHarness] measures four rates while it builds that pool and
 * fails the binding when one falls outside these bounds — not because the type is broken, but
 * because the *evidence* is too thin to say it is not. The measured values print on a green run too
 * ([CrdtConvergenceHarness.measureVacuity]), because a floor whose actual value nobody sees is a
 * floor nobody notices drifting toward.
 *
 * ## The pair definition, stated exactly
 *
 * This is the one thing about these floors that must not be left to the reader, and it has already
 * been read two ways: two independent measurements of `ORSetConvergenceTest` reported **66.7%** and
 * **30.4%** strict ancestry over the same pool builder — a factor of 2.2, entirely definitional.
 *
 * **A "pair" is an ordered pair of distinct pool *positions*.** For a pool of `n` states the
 * denominator is `n(n − 1)` — `(i, j)` and `(j, i)` are two pairs, and `(i, i)` is not a pair. Both
 * numerators are then counted over that same denominator:
 *
 * - **strict-ancestor** counts `(a, b)` with `a ⊔ b == b` and `b ⊔ a != a` — `a` is strictly below
 *   `b`. Only one of the two directions of a comparable pair ever counts, so **a total order reads
 *   50%, not 100%** — that is the ceiling, and `IntMax`'s chain sits exactly on it.
 * - **concurrent** counts `(a, b)` with `a ⊔ b != b` and `b ⊔ a != a` — neither is below the other.
 *   Incomparability is symmetric, so **both** directions count.
 *
 * The two are therefore related by `2 × strictAncestor + concurrent + equal = 100%`, where `equal`
 * is the pairs of positions holding the same value (a no-op step puts one in the pool). That
 * identity is worth checking against any number quoted here: the design's controlled experiment
 * reads `2 × 30.15 + 39.69 = 99.99`, which is what makes its convention recoverable at all.
 *
 * The alternative reading — count **unordered** pairs `{a, b}` in which *either* is a strict
 * ancestor, over `n(n − 1)/2` — is not wrong, it is just twice as large, and it loses the 50% chain
 * ceiling that makes a total order legible at a glance. Everything here uses the ordered one.
 *
 * ## The step rates
 *
 * A **step** is one op of the **random exploration**. Two things a reader will expect to be steps
 * are deliberately not:
 *
 * - Absorbing a peer's state (the pool builder's gossip draw) is not drawn from the alphabet and
 *   has no [OpKind], so there is nothing to classify.
 * - **A critical-shape op is not a step**, and this one is load-bearing. The harness already
 *   asserts that every op of every shape changed the state, so a shape step is a *validated
 *   constant*: it contributes a guaranteed non-no-op and, for the default `assert · retire ·
 *   assert` shape, a guaranteed effective retire. Counting them would let a binding buy headroom
 *   against these bounds by declaring more shapes rather than by searching harder — at the pool
 *   sizes here the default shape alone is worth about 9 points of retirement rate, almost the whole
 *   floor. The bounds exist to measure the part of the budget nobody validates, so the denominator
 *   is exactly that part.
 *
 * That convention is worth stating because it moves the numbers by a lot: `ORSetConvergenceTest`
 * reads **19.5%** no-ops with the shape steps counted and **26.7%** without, over one seed window.
 * The second is the number this floor uses, and the first is the one that would have hidden a real
 * breach.
 *
 * - **effective retire steps** — steps whose op is [OpKind.RETIRE] **and which changed the state**.
 *   The `and` is the whole point: a `remove` of something absent is the lattice identity, and a
 *   generator can spend most of its budget there. This is the floor the design exists for; see
 *   [OpKind] for the controlled experiment showing it is the *only* one of the four that separates
 *   a searching generator from a vacuous one.
 * - **no-op steps** — steps of any kind that left the state unchanged. A ceiling, not a floor.
 *
 * ## The two waivers, and why they are shaped differently
 *
 * [totalOrder] is a **boolean**, because "this pool is a chain" is a structural claim about the
 * type — `IntMax` cannot be given concurrency by a better generator, and pretending otherwise would
 * mean fabricating states it cannot reach. It waives the concurrency floor **only**; a total order
 * still has to clear ancestry (it reads 50%), retirement and no-ops.
 *
 * Grow-only-ness gets **no boolean**. A type with nothing to retire sets `effectiveRetireSteps = 0.0`
 * and says why in a comment — see [GROW_ONLY]. The asymmetry is deliberate: a `growOnly = true` flag
 * would be reachable by *deleting a binding's retiring op*, and deleting the retiring op is exactly
 * the mutation this floor exists to catch. Spelling the waived value out keeps the floor at its
 * default on every binding that has something to retire, so removing that op reds it.
 *
 * @param strictAncestorPairs minimum fraction of ordered pairs that are strict-ancestor pairs.
 * @param concurrentPairs minimum fraction of ordered pairs that are concurrent. Waived by [totalOrder].
 * @param effectiveRetireSteps minimum fraction of steps that retire *and* change the state.
 * @param maxNoOpSteps maximum fraction of steps that leave the state unchanged.
 * @param totalOrder the type's reachable states form a chain, so the concurrency floor is
 *   unreachable rather than unmet. Waives that one floor and nothing else.
 */
public class VacuityFloors(
    public val strictAncestorPairs: Double = 0.15,
    public val concurrentPairs: Double = 0.15,
    public val effectiveRetireSteps: Double = 0.10,
    public val maxNoOpSteps: Double = 0.25,
    public val totalOrder: Boolean = false,
) {
    public companion object {
        /** 15% ancestry, 15% concurrency, 10% effective retires, at most 25% no-ops. */
        public val DEFAULT: VacuityFloors = VacuityFloors()

        /**
         * [DEFAULT] with the retirement floor at zero — for a type whose alphabet has nothing to
         * retire because the *type* has nothing to retire.
         *
         * Use it only where a retiring op could not be written: `GSet` and the counters can only
         * grow, and `DotContext` records dots it has seen. A binding that *could* retire and does
         * not is the vacuity shape itself (`LWWMap` sat at 0.0% retiring steps for as long as it
         * had a binding, with `LWWMap.remove` in the type the whole time) — that wants the op, not
         * this constant.
         */
        public val GROW_ONLY: VacuityFloors = VacuityFloors(effectiveRetireSteps = 0.0)
    }
}
