package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `Double`-valued sibling of [GCounterConvergenceTest], bound here because it had **no**
 * convergence binding at all until #1979 — and a zoo type with no binding is invisible to the byte
 * law this suite exists to enforce, which is the half of #1979 that matters more than the missing
 * annotation.
 *
 * ## What the knobs switch off
 *
 * The byte law compares two fold orders of the same operands, so it can only fail where the
 * *encoding* has more than one order to choose between. Each number below is what keeps that true:
 *
 * - **`replicaCount = 3`.** Every `inc` writes the drawing replica's own slot, so the number of map
 *   keys reachable is exactly the number of replicas. At `1` the map is a singleton, has exactly
 *   one iteration order, and the byte law cannot fail on any input whatsoever; at `2` there are two
 *   fold orders. Three gives six, and makes the middle key's placement load-bearing.
 * - **`opsPerReplica = 8`.** Bounds how far each replica's own slot climbs. It does not gate the
 *   byte law — one op per replica already reaches a three-key map — but it is what gives the
 *   ancestry and concurrency floors a pool deep enough to measure.
 * - **`by = 1..5 halves`.** Exactly-representable doubles, so `piece`'s elementwise max and
 *   [GCounterDouble.value]'s canonical-order sum are exact and the same seed reaches the same
 *   numbers on every target. A draw from `random.nextDouble()` would not change what the byte law
 *   sees, but it would put floating-point rounding between a red and its repro.
 */
internal class GCounterDoubleConvergenceTest : LatticeLawSuite<GCounterDouble>() {
    override fun newHarness(): LatticeLawHarness<GCounterDouble> = LatticeLawHarness(
        initial = GCounterDouble.ZERO,
        // Grow-only: `inc` requires a positive `by` and the join is elementwise max, so no op can
        // take an observation back and `defaultCriticalShapes` yields none. Same reading as
        // `GCounterConvergenceTest`, reached the same way — off the type, not off its name.
        alphabet = listOf(
            LatticeOp("inc", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.inc(replica, by = random.nextInt(1, 6) * 0.5))
            },
        ),
        // No RETIRE op, for the reason argued above — an increment is never taken back.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = GCounterDouble.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )

    /**
     * **Rig receipt: a merged state really does hold one slot per replica.**
     *
     * This binding is the one the JVM cannot judge — `HashMap` bucket order over three short
     * `ReplicaId` keys is largely a function of the key set, so `jvmTest` is green whether or not
     * the type is canonical, and the whole verdict rests on Kotlin/Native and wasmJs. That makes
     * the fixture's only reachability condition — **more than one key in the map** — worth
     * asserting rather than assuming: at `replicaCount = 1` the map is a singleton, has exactly one
     * iteration order, and the byte law is unfalsifiable on every input.
     *
     * Driven through the harness's own alphabet, so the receipt cannot drift from the binding.
     */
    @Test
    fun everyReplicaOwnsASlotInTheMergedState() {
        val harness = newHarness()
        val inc = harness.alphabet.single()
        val random = Random(0)
        val merged = (0 until harness.replicaCount)
            .fold(harness.initial) { state, replicaIndex -> inc.apply(state, replicaIndex, random) }
        assertEquals(
            harness.replicaCount,
            (0 until harness.replicaCount).count { merged.count(ReplicaId("R$it")) > 0.0 },
            "every replica must hold a slot — a single-key map has one iteration order, so the " +
                "byte law could not fail on any input: $merged",
        )
    }
}
