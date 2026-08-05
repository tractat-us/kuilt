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
 * serializer, so "did the encoding shrink?" is free — and wrong, measured over all 16 live bindings:
 *
 * | binding | byte-shrinking steps | does the type actually retire? |
 * |---|---|---|
 * | `LWWRegisterConvergenceTest` | **62.1%** | **no** — `LWWRegister` has no removal at all; short values simply encode shorter |
 * | `TwoPhaseSetConvergenceTest` | **0.0%** | **yes** — tombstones make the state grow |
 * | `RgaConvergenceTest` | **0.0%** | **yes** |
 * | `MovableTreeConvergenceTest` | **0.0%** | **yes** |
 *
 * A proxy that reads 62.1% on the one type with no removal and 0.0% on three that do remove is not
 * a weak signal, it is an inverted one. Hence the declaration.
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
