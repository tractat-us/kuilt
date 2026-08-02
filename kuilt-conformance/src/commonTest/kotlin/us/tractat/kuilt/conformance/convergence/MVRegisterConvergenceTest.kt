package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId

internal class MVRegisterConvergenceTest : CrdtConvergenceSuite<MVRegister<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<MVRegister<String>> = CrdtConvergenceHarness(
        initial = MVRegister.empty(),
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = ReplicaId("R$replicaIndex")
            val value = "v-${random.nextInt(0, 10)}"
            state.set(replica, value)
        },
        serializer = MVRegister.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
