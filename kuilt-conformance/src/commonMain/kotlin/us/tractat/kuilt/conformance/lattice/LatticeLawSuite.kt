package us.tractat.kuilt.conformance.lattice

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
public abstract class LatticeLawSuite<S : Quilted<S>> {

    /** Build and return the harness under test — called once per test method. */
    public abstract fun newHarness(): LatticeLawHarness<S>

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
     * [LatticeLawHarness.runAssociativeLaws] raises are deliberately different: *NOT EQUAL*
     * for an associativity defect, *EQUAL but encode to DIFFERENT bytes* for a canonicality one.
     * They stopped being separate test methods when the two passes were folded into one — running
     * them apart meant computing every join twice, for 18% of this module's Kotlin/Native budget.
     * What the two entry points still buy is a **bounded seed window**: a red here has its cause in
     * `0..7`, and one in [associativeJoinLawsHoldOverUpperSeeds] in `8..15`, which halves the range
     * a repro has to sweep.
     *
     * See [LatticeLawHarness.runAssociativeLaws].
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
     * See [LatticeLawHarness.runExhaustiveSmall].
     *
     * It is cheap for the same reason it is shallow — it only proves the laws over words of a few
     * ops, which is why it does not replace the seed-ranged pair. Neither subsumes the other: this
     * one is exhaustive but small, those are deep but sampled.
     */
    @Test
    public fun associativeJoinLawsHoldOverEveryShortWord() {
        newHarness().runExhaustiveSmall()
    }

    /**
     * The rest of the join-semilattice contract — **commutativity, idempotence and
     * least-upper-bound** — over the causal pool, seeds `0..15`, plus the byte law on the
     * commutativity pair.
     *
     * Breadth, not depth: they read 0 violations on a lattice broken in the way #2086 was broken,
     * where associativity over the same pool reads 500. See
     * [LatticeLawHarness.runOtherJoinLaws] for what that means, and for why commutativity is
     * asserted with no per-binding waiver.
     *
     * Separate from the bracketing tests because it is `O(pool²)` where those are `O(pool³)`: it
     * costs a rounding error beside them, and a red here means something different enough to be
     * worth its own name in the report.
     */
    @Test
    public fun joinCommutesAbsorbsAndIsIdempotent() {
        newHarness().runOtherJoinLawsSeeds(0L..15L)
    }

    /**
     * The one seam every test above skips: a state that has been through the **codec** must be
     * interchangeable with the one that has not. Seeds `0..15`.
     *
     * Replicas in this harness hand each other in-process objects. `Quilter` does not — it encodes a
     * delta, puts the bytes on a `Seam`, and the receiver joins what it decodes. So a binding whose
     * serializer is **lossy but deterministic** satisfies every law above, including both byte laws:
     * each of their comparisons is between two encodings produced by the same lossy path, so the
     * loss cancels on both sides. On the wire it does not cancel, and a removed element resurrects
     * on the next merge.
     *
     * **This is an obligation the suite hands the next binding, more than a hole in today's.** The
     * shipped types are covered by hand — per-type round-trip tests in `:kuilt-crdt`'s `commonTest`
     * and `CanonicalGoldenVectorTest`, which is a hand-maintained list a new type joins only if
     * someone remembers. Without this test, subclassing `LatticeLawSuite` carried zero wire
     * requirement, and every author had to reinvent the check (#2317, part of #2247).
     *
     * Three arms — round-trip value, round-trip bytes, and join-through-the-wire — each catching
     * what the one before it cannot, and three rig receipts asserting the pool was not vacuous. See
     * [LatticeLawHarness.runCodecLaws], which argues all six.
     */
    @Test
    public fun decodedStateJoinsIdenticallyToTheOriginal() {
        val report = newHarness().runCodecLawsSeeds(0L..15L)
        println("${this::class.simpleName} — codec laws over seeds 0..15\n$report")
    }

    /**
     * The generator searched enough for the tests above to mean anything — see [VacuityFloors].
     *
     * **This is not a test of the type; it is a test of the evidence.** A lattice law over a pool
     * whose states are all siblings, or in which nothing was ever retired, holds vacuously. That is
     * #2100 exactly, and it is how #2086 survived a `pieceIsAssociative` property for as long as it
     * did. The four measured rates print on every run, green or red, because a floor whose value
     * nobody sees is a floor nobody notices drifting toward.
     *
     * Seeds `0..15` — the same window the law tests use, so the numbers describe the pools those
     * tests actually ran over rather than a differently-sized sample.
     */
    @Test
    public fun generatorIsNotVacuous() {
        val report = newHarness().checkVacuityFloors(0L..15L)
        println("${this::class.simpleName} — vacuity over seeds 0..15\n$report")
    }
}
