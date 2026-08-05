package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

// Each replica gets a generous initial quota so spend/transfer ops land frequently.
private val R0 = ReplicaId("R0")
private val R1 = ReplicaId("R1")
private val R2 = ReplicaId("R2")
private val REPLICAS = listOf(R0, R1, R2)

internal class BoundedCounterConvergenceTest : LatticeLawSuite<BoundedCounter>() {
    override fun newHarness(): LatticeLawHarness<BoundedCounter> = LatticeLawHarness(
        initial = BoundedCounter.init(mapOf(R0 to 10L, R1 to 10L, R2 to 10L)),
        // Neither op is a RETIRE. Spending and transferring both consume quota, but they do it by
        // *adding* to grow-only tallies — no earlier contribution is withdrawn. Both return null
        // when the quota is short, which is a genuine no-op the harness will see as one.
        alphabet = listOf(
            LatticeOp("spend", OpKind.ASSERT) { state, replicaIndex, random ->
                val patch = state.trySpend(REPLICAS[replicaIndex], random.nextLong(1L, 4L))
                if (patch != null) state.piece(patch) else state
            },
            LatticeOp("transfer", OpKind.ASSERT) { state, replicaIndex, random ->
                val toIndex = (replicaIndex + 1 + random.nextInt(REPLICAS.size - 1)) % REPLICAS.size
                val patch = state.transfer(REPLICAS[replicaIndex], REPLICAS[toIndex], random.nextLong(1L, 4L))
                if (patch != null) state.piece(patch) else state
            },
        ),
        // No RETIRE op, for the reason argued above: spending and transferring consume quota by
        // adding to grow-only tallies, so no earlier contribution is withdrawn.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = BoundedCounter.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
