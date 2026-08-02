package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

// Binds LWWRegister<String> — the single-cell primitive LWWMap is built from (LWWMap has its own
// convergence test). The convergence property is the same: the (timestamp, replicaId) tie-breaker
// must produce the same winner regardless of merge order.
internal class LWWRegisterConvergenceTest : CrdtConvergenceSuite<LWWRegister<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<LWWRegister<String>> = CrdtConvergenceHarness(
        initial = LWWRegister.empty(),
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = ReplicaId("R$replicaIndex")
            // Sparse timestamp space forces frequent ties — exercises the replicaId tie-breaker.
            val timestamp = random.nextLong(0L, 10L)
            val value = "v-${random.nextInt(0, 10)}"
            state.set(replica, timestamp, value)
        },
        serializer = LWWRegister.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
