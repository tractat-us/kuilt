package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId

internal class MVRegisterConvergenceTest : LatticeLawSuite<MVRegister<String>>() {
    override fun newHarness(): LatticeLawHarness<MVRegister<String>> = LatticeLawHarness(
        initial = MVRegister.empty(),
        // `set` supersedes rather than retires — it drops the values it *causally dominates*, but
        // only by putting one in their place, which is the without-a-replacement clause of the
        // test in `OpKind`. There is no other op, so this alphabet declares no RETIRE.
        //
        // `MVRegisterConformanceTest` counts the same `set` as a retirement, and that is not a
        // contradiction: see `OpKind`'s "where a surface may answer differently". A rate has to be
        // able to come out low, and this alphabet is one op — declaring it RETIRE would make the
        // retirement floor below identically `1 - noOpRate`, cleared by the no-op ceiling alone.
        alphabet = listOf(
            LatticeOp("set", OpKind.ASSERT) { state, replicaIndex, random ->
                state.set(ReplicaId("R$replicaIndex"), "v-${random.nextInt(0, 10)}")
            },
        ),
        // No RETIRE op, for the reason argued above.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = MVRegister.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
