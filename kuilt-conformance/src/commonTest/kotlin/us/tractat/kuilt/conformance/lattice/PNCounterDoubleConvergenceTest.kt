package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.PNCounterDouble
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `Double`-valued sibling of [PNCounterConvergenceTest]: mixed increments and decrements
 * populate both [us.tractat.kuilt.crdt.GCounterDouble] halves, so both are exercised for canonical
 * encoding.
 *
 * ## What the knobs switch off
 *
 * - **`replicaCount = 3`.** Every op writes the drawing replica's own slot, so a half reaches as
 *   many keys as there are replicas. At `1` each half is a singleton with one iteration order and
 *   the byte law cannot fail; three gives six orders per half.
 * - **`by = 1..5 halves`.** Exactly-representable doubles, so the joins and the canonical-order sum
 *   are exact and one seed reaches the same numbers on every target, as in
 *   [GCounterDoubleConvergenceTest].
 */
internal class PNCounterDoubleConvergenceTest : LatticeLawSuite<PNCounterDouble>() {
    override fun newHarness(): LatticeLawHarness<PNCounterDouble> = LatticeLawHarness(
        initial = PNCounterDouble.ZERO,
        // `decrement` is NOT a RETIRE, for the reason PNCounterConvergenceTest gives: it adds to the
        // second grow-only half and takes back no earlier contribution.
        alphabet = listOf(
            LatticeOp("inc", OpKind.ASSERT) { state, replicaIndex, random ->
                state.piece(state.increment(ReplicaId("R$replicaIndex"), random.nextInt(1, 6) * 0.5))
            },
            LatticeOp("dec", OpKind.ASSERT) { state, replicaIndex, random ->
                state.piece(state.decrement(ReplicaId("R$replicaIndex"), random.nextInt(1, 6) * 0.5))
            },
        ),
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = PNCounterDouble.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )

    /**
     * **Rig receipt: a merged state holds a slot per replica in both halves.** A half with one key
     * has one iteration order, and the byte law could not fail on it. Driven through the harness's
     * own alphabet, so the receipt cannot drift from the binding.
     */
    @Test
    fun everyReplicaOwnsASlotInBothHalves() {
        val harness = newHarness()
        val (inc, dec) = harness.alphabet
        val random = Random(0)
        val merged = (0 until harness.replicaCount).fold(harness.initial) { state, replicaIndex ->
            dec.apply(inc.apply(state, replicaIndex, random), replicaIndex, random)
        }
        // The halves are private; their toString names each slot as `R<i>)=<count>`, once per half.
        val printed = merged.toString()
        val withBoth = (0 until harness.replicaCount).count { i -> printed.split("R$i)=").size - 1 == 2 }
        assertEquals(harness.replicaCount, withBoth, "every replica must hold a slot in both halves: $printed")
    }
}
