package us.tractat.kuilt.crdt

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioural properties of [Rga] that are **not** lattice laws.
 *
 * These four re-home the only non-law properties the deleted JVM-only jqwik surface carried for
 * [Rga] (#2101). The lattice laws themselves — idempotence, commutativity, associativity, least
 * upper bound, and associativity along a causal trajectory — are asserted for every type by
 * `:kuilt-conformance`'s shared randomised suite, on every target; nothing here duplicates them.
 * What is left is behaviour specific to a sequence CRDT: replicas that saw different op orders
 * agree on the *visible list*, tombstones hide elements from it, positional insert lands where it
 * says it does, and concurrent inserts after one predecessor are ordered by id.
 *
 * **Determinism.** jqwik drew its operands from an unseeded generator and reported a seed on
 * failure. There is no equivalent on Kotlin/Native or wasmJs, so each property here sweeps a fixed
 * band of seeds through `Random(seed)`: the same trajectories on every target and every run, and a
 * failure names the seed that produced it. Where the shape is finite it is enumerated outright
 * rather than sampled — see [concurrentInsertsAfterSamePredecessorAreOrdered].
 */
class RgaTest {

    /**
     * Three replicas apply their own op sequences and then exchange everything. Each merges in a
     * different order; all three must agree on [Rga.toList], not merely on internal state.
     */
    @Test
    fun threeReplicasConverge() {
        for (seed in 0 until SEEDS) {
            val random = Random(seed)
            val stateA = stateFor(ReplicaId("A"), random)
            val stateB = stateFor(ReplicaId("B"), random)
            val stateC = stateFor(ReplicaId("C"), random)

            val convergedA = stateA.piece(stateB).piece(stateC)
            val convergedB = stateB.piece(stateC).piece(stateA)
            val convergedC = stateC.piece(stateA).piece(stateB)

            assertEquals(convergedA.toList(), convergedB.toList(), "seed $seed: A and B diverged")
            assertEquals(convergedB.toList(), convergedC.toList(), "seed $seed: B and C diverged")
        }
    }

    /**
     * Tombstones exclude elements from [Rga.toList]: removing at index 0 shrinks the visible list
     * by exactly one and leaves the tail. The removed element's id stays in the op-log so later
     * causal references to it remain resolvable — which is why the assertion is on `toList()` and
     * not on the op count.
     */
    @Test
    fun removedElementsAreExcludedFromToList() {
        var exercised = 0
        for (seed in 0 until SEEDS) {
            val state = stateFor(ReplicaId("A"), Random(seed))
            val visible = state.toList()
            if (visible.isEmpty()) continue
            exercised++

            val (afterRemove, _) = assertNotNull(
                state.removeAt(0),
                "seed $seed: removeAt(0) returned null on a non-empty Rga",
            )
            val newVisible = afterRemove.toList()
            assertEquals(visible.size - 1, newVisible.size, "seed $seed: wrong size after remove")
            assertEquals(visible.drop(1), newVisible, "seed $seed: remaining list is not the tail")
        }
        // Without this the property passes vacuously the day the generator stops producing
        // non-empty states — the failure mode #2101 exists to close.
        assertTrue(exercised > 0, "vacuous: no seed in 0..<$SEEDS produced a non-empty Rga")
    }

    /** [Rga.insertAt] places the value at the visible position it names, and grows the list by one. */
    @Test
    fun insertAtPlacesElementAtCorrectIndex() {
        for (seed in 0 until SEEDS) {
            val state = stateFor(ReplicaId("A"), Random(seed))
            val visible = state.toList()
            val index = visible.size / 2
            val (newState, _) = state.insertAt(ReplicaId("test"), index, MARKER)
            val newVisible = newState.toList()

            assertEquals(MARKER, newVisible[index], "seed $seed: marker not at index $index")
            assertEquals(visible.size + 1, newVisible.size, "seed $seed: wrong size after insert")
        }
    }

    /**
     * Two replicas insert after the same predecessor without seeing each other. The merged list
     * puts the **larger** [RgaId] first — higher lamport, `replicaId` breaking a tie
     * ([RgaId.compareTo]).
     *
     * The shape is finite once the predecessor is fixed at [RgaId.HEAD], so this enumerates it
     * rather than sampling: every lamport in [1, [LAMPORTS]] against all three orderings —
     * A ahead, B ahead, and equal lamports where only the replica id separates them. jqwik's
     * version only ever generated B ahead, leaving the "A wins" branch of its own assertion dead.
     */
    @Test
    fun concurrentInsertsAfterSamePredecessorAreOrdered() {
        for (lamport in 1L..LAMPORTS) {
            for ((lamportA, lamportB) in listOf(
                lamport to lamport + 1L, // B ahead
                lamport + 1L to lamport, // A ahead
                lamport to lamport, // tie — replica id decides
            )) {
                val insertA = RgaOp.Insert(
                    id = RgaId(lamport = lamportA, replicaId = ReplicaId("A"), seq = 1L),
                    value = "valA-$lamportA",
                    after = RgaId.HEAD,
                )
                val insertB = RgaOp.Insert(
                    id = RgaId(lamport = lamportB, replicaId = ReplicaId("B"), seq = 1L),
                    value = "valB-$lamportB",
                    after = RgaId.HEAD,
                )
                val merged = Rga.empty<String>().apply(insertA).piece(Rga.empty<String>().apply(insertB))
                val list = merged.toList()

                val (first, second) =
                    if (insertA.id > insertB.id) insertA.value to insertB.value
                    else insertB.value to insertA.value
                assertTrue(
                    list.indexOf(first) < list.indexOf(second),
                    "expected $first (larger id) before $second: $list",
                )
            }
        }
    }

    /** Replica [replica]'s state after a short run of appends interleaved with removes. */
    private fun stateFor(replica: ReplicaId, random: Random): Rga<String> =
        opsFor(replica, random).fold(Rga.empty()) { state, op -> state.apply(op) }

    /**
     * A short op sequence for one replica: up to [MAX_OPS] values drawn from a small alphabet,
     * appended at the end, with roughly every third op turned into a remove so tombstones are
     * exercised. Ported from the jqwik provider of the same shape.
     */
    private fun opsFor(replica: ReplicaId, random: Random): List<RgaOp<String>> {
        val values = List(random.nextInt(0, MAX_OPS + 1)) { "v${random.nextInt(0, ALPHABET)}" }
        val ops = mutableListOf<RgaOp<String>>()
        var state = Rga.empty<String>()
        for (value in values) {
            val size = state.size
            if (size > 0 && ops.size % 3 == 0) {
                val removed = state.removeAt((size / 2).coerceIn(0, size - 1))
                if (removed != null) {
                    state = removed.first
                    ops += removed.second
                }
            } else {
                val (newState, op) = state.insertAt(replica, state.size, value)
                state = newState
                ops += op
            }
        }
        return ops
    }

    private companion object {
        /** Seed band swept by every randomised property here. */
        const val SEEDS = 64

        /** Upper bound on the enumerated lamport in [concurrentInsertsAfterSamePredecessorAreOrdered]. */
        const val LAMPORTS = 100L

        const val MAX_OPS = 6
        const val ALPHABET = 10
        const val MARKER = "X"
    }
}
