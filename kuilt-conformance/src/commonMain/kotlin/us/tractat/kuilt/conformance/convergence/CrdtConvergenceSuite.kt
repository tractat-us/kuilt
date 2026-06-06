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
}
