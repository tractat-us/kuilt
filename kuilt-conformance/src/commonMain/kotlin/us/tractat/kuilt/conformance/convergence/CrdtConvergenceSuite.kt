package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.Quilted
import kotlin.test.Test

/**
 * Sibling to [us.tractat.kuilt.conformance.QuiltedConformanceSuite]. Subclass and implement
 * [newHarness] to bind a CRDT type.
 *
 * Provides convergence tests that drive N replicas through random op sequences, then assert every
 * delivery permutation produces the same merged value. Lives in `commonMain` of `:kuilt-conformance`
 * so any module's `commonTest` can subclass it.
 *
 * Multiplatform: runs on JVM, wasmJs, and native.
 */
public abstract class CrdtConvergenceSuite<S : Quilted<S>> {

    /** Build and return the harness under test — called once per test method. */
    public abstract fun newHarness(): CrdtConvergenceHarness<S>

    /** Run 32 seeds (~6 permutations each at replicaCount=3 → 192 convergence assertions). */
    @Test
    public fun convergesAcrossSeeds() {
        newHarness().runSeeds(0L..31L)
    }

    /** Pin seed 0 for regression repro — if a specific seed fails, add a test here. */
    @Test
    public fun convergesAtSeedZero() {
        newHarness().run(seed = 0L)
    }

    /**
     * Both bracketing laws over the lower half of the seed budget, seeds `0..7`.
     *
     * `piece` must be associative over *reachable, causally related* states, and the two
     * bracketings of an equal join must additionally encode to identical bytes. This is the pair
     * [convergesAcrossSeeds] cannot check. That test folds a fixed set of operands in every order
     * and asks whether they all land in the same place; a join that loses a contribution depending
     * on bracketing still passes it, because every fold there absorbs the same operands and the
     * loss heals as soon as the missing one arrives. Associativity is the stronger statement that
     * `b ⊔ c` delivered as one digest is interchangeable with `b` then `c` — the statement
     * anti-entropy, delta batching, and state hashing all rest on. The byte law is the one #1955's
     * root-hash gate rests on.
     *
     * **Which law broke is named by the failure, not by the test.** The two messages
     * [CrdtConvergenceHarness.runAssociativeLaws] raises are deliberately different: *NOT EQUAL*
     * for an associativity defect, *EQUAL but encode to DIFFERENT bytes* for a canonicality one.
     * They stopped being separate test methods when the two passes were folded into one — running
     * them apart meant computing every join twice, for 18% of this module's Kotlin/Native budget.
     * What the two entry points still buy is a **bounded seed window**: a red here has its cause in
     * `0..7`, and one in [associativeJoinLawsHoldOverUpperSeeds] in `8..15`, which halves the range
     * a repro has to sweep.
     *
     * See [CrdtConvergenceHarness.runAssociativeLaws].
     */
    @Test
    public fun associativeJoinLawsHoldOverLowerSeeds() {
        newHarness().runAssociativeLawsSeeds(0L..7L)
    }

    /**
     * The same two laws over the upper half of the seed budget, seeds `8..15`.
     *
     * See [associativeJoinLawsHoldOverLowerSeeds] for both laws and for why they share one pass.
     */
    @Test
    public fun associativeJoinLawsHoldOverUpperSeeds() {
        newHarness().runAssociativeLawsSeeds(8L..15L)
    }

    /**
     * The same two laws over **every short word** the binding's alphabet can spell — and, when one
     * of them breaks, the shortest word that breaks it.
     *
     * The two seed-ranged tests above search *wide*: three replicas, gossip, a fourteen-state pool,
     * sixteen seeds. They are good at finding that something is wrong and bad at saying what.
     * A red there hands you three states out of a pool of fourteen, reached by a draw order you
     * have to re-derive in your head from a seed. This test searches *narrow and complete*: one
     * replica, every word of length 1..L, in length order — so the first word that fails is the
     * shortest word that can, and the failure prints it in the binding's own op names.
     *
     * That pairing is deliberate, and it is what a property-based library's shrinker was doing for
     * the JVM-only suite this one replaces. A shrinker narrows *one* failure it happened to find,
     * to a locally minimal synthetic operand list. Enumerating short words instead gives the
     * **globally** shortest reachable trajectory, and gives it as a word that reproduces on its own.
     * See [CrdtConvergenceHarness.runExhaustiveSmall].
     *
     * It is cheap for the same reason it is shallow — it only proves the laws over words of a few
     * ops, which is why it does not replace the seed-ranged pair. Neither subsumes the other: this
     * one is exhaustive but small, those are deep but sampled.
     */
    @Test
    public fun associativeJoinLawsHoldOverEveryShortWord() {
        newHarness().runExhaustiveSmall()
    }
}
