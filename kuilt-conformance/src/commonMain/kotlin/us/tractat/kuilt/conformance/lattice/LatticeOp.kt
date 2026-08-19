package us.tractat.kuilt.conformance.lattice

import kotlin.random.Random

/**
 * Whether an op **asserts** something new or **retires** something an earlier op asserted.
 *
 * ## The test, stated once
 *
 * **An op retires when it takes an observation back without putting another in its place.**
 *
 * Read that off the type's *observable value*, never its encoding. `remove`, `unset`, `leave` and
 * `disable` retire; `MovableTree.move` retires, because the node stops being under its old parent,
 * even though the move log only grows. A register's `set` does **not**: it stops the old value
 * being shown, but only by showing a new one in the same place. That is supersession, and the
 * without-a-replacement clause is exactly what excludes it. `PNCounter.decrement` does not either
 * — it adds to a second grow-only tally, and the earlier increment's contribution stays where it
 * was.
 *
 * **This is the definition, and it is meant to be stated only here.** A surface that classifies an
 * op cites this section rather than re-deriving it — [VacuityFloors.NOTHING_TO_RETIRE], a
 * binding's alphabet comment, and across the module
 * [us.tractat.kuilt.conformance.QuiltedConformanceSuite.retirementIsMeaningful] all do. Adding a
 * fourth statement of it is the defect, not the documentation.
 *
 * ## Where a surface may answer differently, and the only reason it may
 *
 * `QuiltedConformanceSuite.retirementIsMeaningful` reads a register's `set` **as** a retirement —
 * the opposite answer, on the same op, of the one above — and is right to. The licence is not
 * about the op:
 *
 * > **Classify strictly where the answer is averaged. A surface may classify generously where the
 * > answer is checked.**
 *
 * Here the answer is **averaged**, so a generous label destroys the measurement.
 * [VacuityFloors.effectiveRetireSteps] is a rate over every step the pool builder took, and
 * [LatticeLawHarness.measureVacuity] counts a step toward it when the op is [RETIRE] *and* the
 * step changed the state. On an alphabet whose ops are *all* [RETIRE] those two counters partition
 * the steps, so the retirement rate is identically `1 − noOpRate` — pinned at ≥ 75% by the 25%
 * no-op ceiling alone, and unable to come out low whatever the generator does.
 * `MVRegisterConvergenceTest`'s alphabet is a single `set`; declaring it [RETIRE] would clear its
 * retirement floor by construction. That is the very vacuity this floor exists to catch, arriving
 * through the classification instead of through the generator.
 *
 * There the answer is **checked**, so a generous label costs an obligation instead.
 * `retirementIsMeaningful` gates one constructed triple in which every step is asserted: the named
 * subject must be shown, then **not** shown, then shown again. Reading supersession as retirement
 * buys one more checked shape and can inflate nothing, because nothing is averaged — and if a
 * register's `set` did not really stop the old value being shown, the guard reds rather than
 * passes. Both surfaces are therefore right about `set`, for a reason each states where it
 * declares.
 *
 * ## Why the definition lives here
 *
 * Because this KDoc has already been wrong about retirement twice. It claimed `LWWRegister` "has
 * no removal at all" — false when written, since `unset` is a documented tombstone, and
 * contradicted outright once #2142 bound an `unset-high` op as [RETIRE]; #2146 corrected it. Then
 * this surface and surface 3 were caught shipping opposite tests for the same `set` on the same
 * type (#2159). A definition restated in three places drifts in three places; stated once and
 * cited, a surface that disagrees has to say why — which is the section above.
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
 * The rows are a dated receipt, not the live numbers: they predate #2145's leading asserts. Those
 * move the **top** row — a bound generator now spends fewer steps at the lattice bottom, so its
 * ancestry falls and its concurrency and retirement rise — and leave the **bottom** row exactly
 * where it is, because an alphabet with nothing to retire gets no leading assert at all. The
 * asymmetry the table exists to show is therefore, if anything, wider than it reads here.
 * `VacuityFloorSelfTest` runs both arms live rather than quoting them.
 *
 * Ancestry, concurrency and join-non-triviality — the three a reviewer reaches for, and the three a
 * generic `Quilted` can compute — are all *satisfied* by the arm that finds nothing. Concurrency and
 * join-non-triviality even go **up** when the removes are deleted. The retirement rate is the only
 * column that separates a searching generator from a vacuous one.
 *
 * **The asymmetry is a standing assertion, not only this table.** `VacuityFloorSelfTest`
 * (`:kuilt-conformance`'s `commonTest`) runs the two arms live — the `ORMapConvergenceTest` harness
 * as it stands, and that same harness with every [RETIRE] op filtered out of its alphabet — and
 * asserts the vacuous one breaches the retirement floor while **clearing all three others**. It
 * prints both reports, so the contrast is visible on a green run rather than only here.
 *
 * **The table is not re-derivable from its own description, and the self-test does not try to be.**
 * "Same generator, removes deleted" leaves the branch structure open, and the branch structure
 * decides where the pool cap truncates — so it decides the triple and step counts. Four spellings
 * were measured while closing #2152 and none reproduces both the published 47,059 triples and the
 * published percentages; all four reach this same conclusion, which is what is pinned. (Note also
 * that join-non-triviality was the probe's own measurement: the *shipped* fourth rate is the no-op
 * ceiling, and [VacuityReport] carries no inner-join column.)
 */
public enum class OpKind {
    /** Adds an observation: a put, an add, an insert, an increment. */
    ASSERT,

    /**
     * Withdraws an observation an earlier op made: a remove, a departure, a re-parent.
     *
     * Whether an op qualifies is the test at the top of [OpKind] — not restated here, because
     * restating it is how the two surfaces drifted apart in the first place.
     */
    RETIRE,
}

/**
 * One named operation a binding can perform against its state.
 *
 * The alphabet is the binding's whole vocabulary: [LatticeLawHarness] draws from it to build
 * the randomised causal pool, and [LatticeLawHarness.criticalShapes] names its members to
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
 * pool it ran over. [LatticeLawHarness] measures four rates while it builds that pool and
 * fails the binding when one falls outside these bounds — not because the type is broken, but
 * because the *evidence* is too thin to say it is not. The measured values print on a green run too
 * ([LatticeLawHarness.measureVacuity]), because a floor whose actual value nobody sees is a
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
 * A **step** is one op the pool builder applied to a replica: **every op of every critical shape,
 * every replica's leading assert, plus every op of the random exploration**. Absorbing a peer's
 * state (the gossip draw) is not a step — it is not drawn from the alphabet and has no [OpKind] to
 * classify.
 *
 * The leading asserts are [LatticeLawHarness.leadEveryReplicaWithAnAssert] — one asserting op per
 * replica, on any alphabet with something to retire, so that no replica takes its first exploration
 * draw from the lattice bottom. They are *constructed* steps and count for the same three reasons
 * the shape steps do.
 *
 * **Whether the constructed ops count is a real choice, and it moves the numbers a long way.**
 * `ORSetConvergenceTest` reads **8.6%** no-ops counting them and **15.0%** excluding them, on the
 * same pool over the same seeds. The case for excluding them is that the harness already asserts
 * every constructed step changed the state, so they are validated constants that dilute a ceiling
 * and inflate a floor. They are counted anyway, for three reasons:
 *
 * 1. **They are not free.** A shape whose step does not move the state fails the binding outright,
 *    so a shape cannot be padding — declaring one is doing the work, not dodging it. The same check
 *    guards the leading asserts.
 * 2. **They are the design's own thesis.** Constructed shapes are what reach the interesting
 *    configuration on *every* seed rather than on a lucky one. A denominator that excludes exactly
 *    the constructed part measures only the part this suite considers weaker.
 * 3. **Consistency with the pair rates.** Ancestry and concurrency are measured over the whole
 *    pool, constructed states included. Excluding constructed steps would have one table describing
 *    two different populations.
 *
 * The cost is that the default `assert · retire · assert` shape contributes about 9 points of
 * retirement rate at these pool sizes — most of the 10% floor — so **a binding must not be read as
 * clearing the retirement floor on its shape alone.** Every retiring binding in the tree clears it
 * with room to spare on exploration too (the lowest is 21.3%), and a binding that sat near 10%
 * would be worth looking at rather than passing.
 *
 * **That margin is a property of the binding, not of the floor, and #2158 is the standing record of
 * the difference.** Since #2145 every retiring binding clears the 10% floor on its *exploration*
 * retirement alone — the weakest is `JsonCrdt` at 11.5% of all steps over seeds `0..63`, where
 * before the leading asserts it was 10.0% and therefore had no margin at all. What has not changed
 * is the floor's own discriminating power: the shape still contributes ~8.6 points of the 10%, so a
 * *mutant* whose retirement is dead on two of three replicas still clears it (an `ORSet` shaped that
 * way measures 17.0%). Read this floor as evidence about a healthy binding's generator, not as a
 * detector for retirement that has died unevenly across replicas.
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
 * Having nothing to retire gets **no boolean**. Such a binding sets `effectiveRetireSteps = 0.0`
 * and says why in a comment — see [NOTHING_TO_RETIRE]. The asymmetry is deliberate: a
 * `growOnly = true` flag would be reachable by *deleting a binding's retiring op*, and deleting the
 * retiring op is exactly the mutation this floor exists to catch. Spelling the waived value out
 * keeps the floor at its default on every binding that has something to retire, so removing that op
 * reds it rather than quietly reclassifying it.
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
         * [DEFAULT] with the retirement floor at zero — for a binding whose alphabet declares no
         * [OpKind.RETIRE] op because **the type has nothing to retire**.
         *
         * Named for the claim rather than for a shape, because the bindings that qualify are not
         * all the same shape. `GSet`, `GCounter`, `PNCounter` and `IntMax` genuinely only grow, and
         * `DotContext` only records dots it has seen. But `MVRegister` *supersedes* — `set` drops
         * the values it causally dominates — and `BoundedCounter`'s `spend` and `transfer` consume
         * quota by adding to grow-only tallies. Neither grows in the naive sense; both qualify
         * under the test at the top of [OpKind], and each binding argues it where it declares this.
         *
         * **Reach for it only when a retiring op could not be written.** A binding that *could*
         * retire and does not is the vacuity shape itself, not a candidate for a waiver: `LWWMap`
         * sat at 0.0% retiring steps for as long as it had a binding, with `LWWMap.remove` in the
         * type the entire time. That wanted the op, which is what it got.
         */
        public val NOTHING_TO_RETIRE: VacuityFloors = VacuityFloors(effectiveRetireSteps = 0.0)
    }
}
