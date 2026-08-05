package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.ReplicaId

/**
 * The clock a write uses: one above whatever this replica's slot already holds.
 *
 * **Derived from the state rather than drawn, and that is the whole of Task 4 on this binding.**
 * Both [EphemeralMap.put] and [EphemeralMap.leave] return the receiver unchanged unless the clock
 * strictly exceeds the replica's current one, so a generator drawing from a sparse pool spends most
 * of its budget on writes the type discards: **51.1%** of steps were no-ops that way (`put`
 * 172/331, `leave` 173/344 over seeds `0..63`). It also put the retire-and-re-assert shape out of
 * reach — `put · leave · put` lands only when three independent draws happen to ascend, and the
 * harness rightly fails a shape whose steps do not change the state, so this binding shipped with
 * no critical shape at all. One above the current clock makes every op effective by construction
 * and the shape reachable on every seed.
 *
 * **The sparse pool was not buying what its comment claimed.** Its stated purpose was equal-clock
 * present-vs-null collisions, exercising [EphemeralMap.piece]'s "present beats null" tie-break —
 * but this harness could not produce one either way. A slot is single-writer and a replica's own
 * history is monotone under *any* draw (both mutators reject a clock that does not advance), so no
 * two pool states can hold one replica's slot at the same clock with different values; peers only
 * ever carry copies of a state the owner already published. `EphemeralMapTest` builds that
 * collision directly, which is where it belongs.
 */
private fun nextClock(state: EphemeralMap<String>, replica: ReplicaId): Long =
    (state.entries[replica]?.clock ?: -1L) + 1L

/**
 * Convergence stress-test for [EphemeralMap].
 *
 * Each replica writes only to its own slot (single-writer contract), but the merge combines all
 * replicas' views and must converge identically under every delivery permutation.
 */
internal class EphemeralMapConvergenceTest : LatticeLawSuite<EphemeralMap<String>>() {

    private val replicaIds = List(3) { ReplicaId("R$it") }

    override fun newHarness(): LatticeLawHarness<EphemeralMap<String>> = LatticeLawHarness(
        initial = EphemeralMap.empty(),
        alphabet = listOf(
            LatticeOp("put", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = replicaIds[replicaIndex]
                // The value stays drawn. A clock is never reused for a replica, so no two writes
                // share a tag and the value is free to vary — and it is the only thing that varies
                // in the encoding once the clock is a counter.
                state.put(replica, "v${random.nextInt(4)}", nextClock(state, replica))
            },
            LatticeOp("leave", OpKind.RETIRE) { state, replicaIndex, _ ->
                val replica = replicaIds[replicaIndex]
                state.leave(replica, nextClock(state, replica))
            },
        ),
        // The default shape, `put · leave · put`, and it now lands on every seed: clocks c, c+1,
        // c+2 on replica 0. The re-assert outranks the departure, so a final null can only mean the
        // join kept a tombstone the higher-clocked presence entry should have replaced.
        serializer = EphemeralMap.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
