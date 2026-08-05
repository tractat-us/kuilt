package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga

// Random-position inserts plus removes force concurrent same-predecessor siblings and
// tombstone merges — exactly the patterns that reveal ordering-tiebreak bugs.
internal class RgaConvergenceTest : LatticeLawSuite<Rga<String>>() {
    override fun newHarness(): LatticeLawHarness<Rga<String>> = LatticeLawHarness(
        initial = Rga.empty(),
        // `insert-head` / `remove-head` both pin index 0, so the derived shape
        // `insert-head · remove-head · insert-roam` retires exactly the element it just inserted.
        // The roaming pair keeps the random-position behaviour that forces concurrent
        // same-predecessor siblings.
        alphabet = listOf(
            LatticeOp("insert-head", OpKind.ASSERT) { state, replicaIndex, random ->
                state.insertAt(ReplicaId("R$replicaIndex"), 0, "v$replicaIndex.${random.nextInt(100)}").first
            },
            LatticeOp("insert-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val index = random.nextInt(state.size + 1)
                state.insertAt(ReplicaId("R$replicaIndex"), index, "v$replicaIndex.${random.nextInt(100)}").first
            },
            LatticeOp("remove-head", OpKind.RETIRE) { state, _, _ ->
                if (state.size > 0) state.removeAt(0)?.first ?: state else state
            },
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                if (state.size > 0) state.removeAt(random.nextInt(state.size))?.first ?: state else state
            },
        ),
        serializer = Rga.wireSerializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
