package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId

internal class MVRegisterConvergenceTest : CrdtConvergenceSuite<MVRegister<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<MVRegister<String>> = CrdtConvergenceHarness(
        initial = MVRegister.empty(),
        // `set` supersedes rather than retires: it drops the values it *causally dominates*, which
        // is the register's whole semantics rather than a withdrawal of an assertion. There is no
        // op that takes a value back without putting one in its place, so no RETIRE is declared.
        alphabet = listOf(
            LatticeOp("set", OpKind.ASSERT) { state, replicaIndex, random ->
                state.set(ReplicaId("R$replicaIndex"), "v-${random.nextInt(0, 10)}")
            },
        ),
        serializer = MVRegister.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
