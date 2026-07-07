package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.ReplicaId

// Random-position inserts plus removes force concurrent same-anchor siblings and
// tombstone merges — the patterns that reveal Fugue tree-ordering (non-interleaving) bugs.
internal class FugueConvergenceTest : CrdtConvergenceSuite<Fugue<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<Fugue<String>> = CrdtConvergenceHarness(
        initial = Fugue.empty(),
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = ReplicaId("R$replicaIndex")
            if (state.size > 0 && random.nextInt(4) == 0) {
                state.removeAt(random.nextInt(state.size))?.first ?: state
            } else {
                val index = random.nextInt(state.size + 1)
                state.insertAt(replica, index, "v$replicaIndex.${random.nextInt(100)}").first
            }
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
