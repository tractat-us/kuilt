package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

internal class LWWMapConvergenceTest : CrdtConvergenceSuite<LWWMap<String, String>>() {
    override fun newHarness(): CrdtConvergenceHarness<LWWMap<String, String>> = CrdtConvergenceHarness(
        initial = LWWMap.empty(),
        // No RETIRE op — deliberately, and this is a **known gap, not a property of the type**.
        // `LWWMap.remove` exists; the generator has never called it, so this binding retires on
        // 0.0% of its steps. Adding the op is #2101's Task 4, which owns this file next; declaring
        // a RETIRE here that nothing performs would be exactly the vacuity the alphabet exists to
        // make visible. Same for the tag-uniqueness precondition this `set` violates.
        alphabet = listOf(
            LatticeOp("set", OpKind.ASSERT) { state, replicaIndex, random ->
                val r = ReplicaId("R$replicaIndex")
                // Sparse timestamp space forces frequent ties — exercises the replicaId tie-breaker.
                val ts = random.nextLong(0L, 10L)
                val key = "k-${random.nextInt(0, 3)}"
                val value = "v-${random.nextInt(0, 10)}"
                state.piece { it.set(r, ts, key, value) }
            },
        ),
        serializer = LWWMap.serializer(String.serializer(), String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
