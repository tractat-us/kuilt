package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.ReplicaId

// Random-position inserts plus removes force concurrent same-anchor siblings and
// tombstone merges — the patterns that reveal Fugue tree-ordering (non-interleaving) bugs.
internal class FugueConvergenceTest : CrdtConvergenceSuite<Fugue<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<Fugue<String>> = CrdtConvergenceHarness(
        initial = Fugue.empty(),
        // Pinned head ops so the derived shape `insert-head · remove-head · insert-roam` retires
        // exactly the element it just inserted; the roaming pair keeps the random-position
        // behaviour that forces concurrent same-anchor siblings.
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
        serializer = Fugue.wireSerializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
