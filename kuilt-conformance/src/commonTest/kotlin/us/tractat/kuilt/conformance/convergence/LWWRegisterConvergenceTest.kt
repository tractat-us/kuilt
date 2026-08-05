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
        // `LWWRegister` has no removal at all, so nothing here is a RETIRE. It is also the type the
        // free byte-size proxy gets most wrong — 62.1% of its steps shrink the encoding, purely
        // because a shorter value string encodes shorter. See `OpKind`.
        //
        // The tag-uniqueness precondition this generator violates (same `(replica, timestamp)`,
        // two different values) is #2101's Task 4, not this one.
        alphabet = listOf(
            LatticeOp("set", OpKind.ASSERT) { state, replicaIndex, random ->
                // Sparse timestamp space forces frequent ties — exercises the replicaId tie-breaker.
                val timestamp = random.nextLong(0L, 10L)
                state.set(ReplicaId("R$replicaIndex"), timestamp, "v-${random.nextInt(0, 10)}")
            },
        ),
        serializer = LWWRegister.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
