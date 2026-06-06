package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId

// Fixed element pool forces frequent concurrency collisions — exactly the pattern that reveals
// add-wins vs remove-wins ambiguity bugs.
private val ELEMENTS = listOf("elem-0", "elem-1", "elem-2", "elem-3")

internal class ORSetConvergenceTest : CrdtConvergenceSuite<ORSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<ORSet<String>> = CrdtConvergenceHarness(
        initial = ORSet.empty(),
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = ReplicaId("R$replicaIndex")
            val element = ELEMENTS[random.nextInt(ELEMENTS.size)]
            if (state.contains(element) && random.nextBoolean()) {
                state.remove(element)
            } else {
                state.add(replica, element)
            }
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
