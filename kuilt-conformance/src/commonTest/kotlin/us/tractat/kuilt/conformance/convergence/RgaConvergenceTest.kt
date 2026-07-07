package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga

// Random-position inserts plus removes force concurrent same-predecessor siblings and
// tombstone merges — exactly the patterns that reveal ordering-tiebreak bugs.
internal class RgaConvergenceTest : CrdtConvergenceSuite<Rga<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<Rga<String>> = CrdtConvergenceHarness(
        initial = Rga.empty(),
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
