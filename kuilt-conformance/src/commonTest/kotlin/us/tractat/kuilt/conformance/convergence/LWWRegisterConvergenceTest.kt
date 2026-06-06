package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

// Note: LWWMap is not yet in kuilt-crdt; this binds LWWRegister<String> instead, which is the
// primitive the plan's LWWMap would be built from. The convergence property is the same: the
// (timestamp, replicaId) tie-breaker must produce the same winner regardless of merge order.
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
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
