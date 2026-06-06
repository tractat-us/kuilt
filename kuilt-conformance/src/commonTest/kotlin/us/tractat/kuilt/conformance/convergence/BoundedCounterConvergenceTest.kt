package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

// Each replica gets a generous initial quota so spend/transfer ops land frequently.
private val R0 = ReplicaId("R0")
private val R1 = ReplicaId("R1")
private val R2 = ReplicaId("R2")
private val REPLICAS = listOf(R0, R1, R2)

internal class BoundedCounterConvergenceTest : CrdtConvergenceSuite<BoundedCounter>() {
    override fun newHarness(): CrdtConvergenceHarness<BoundedCounter> = CrdtConvergenceHarness(
        initial = BoundedCounter.init(mapOf(R0 to 10L, R1 to 10L, R2 to 10L)),
        gen = OperationGenerator { state, replicaIndex, random ->
            val from = REPLICAS[replicaIndex]
            val amount = random.nextLong(1L, 4L)
            when {
                // 50% spend, 50% transfer to a random peer
                random.nextBoolean() -> {
                    val patch = state.trySpend(from, amount)
                    if (patch != null) state.piece(patch) else state
                }
                else -> {
                    val toIndex = (replicaIndex + 1 + random.nextInt(REPLICAS.size - 1)) % REPLICAS.size
                    val to = REPLICAS[toIndex]
                    val patch = state.transfer(from, to, amount)
                    if (patch != null) state.piece(patch) else state
                }
            }
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
