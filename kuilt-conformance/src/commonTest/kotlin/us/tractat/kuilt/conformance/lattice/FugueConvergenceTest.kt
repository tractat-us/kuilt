package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.ReplicaId

// Random-position inserts plus removes force concurrent same-anchor siblings and
// tombstone merges — the patterns that reveal Fugue tree-ordering (non-interleaving) bugs.
internal class FugueConvergenceTest : LatticeLawSuite<Fugue<String>>() {
    override fun newHarness(): LatticeLawHarness<Fugue<String>> = LatticeLawHarness(
        initial = Fugue.empty(),
        // Pinned head ops so the derived shape `insert-head · remove-head · insert-roam` retires
        // exactly the element it just inserted; the roaming pair keeps the random-position
        // behaviour that forces concurrent same-anchor siblings.
        alphabet = listOf(
            LatticeOp("insert-head", OpKind.ASSERT) { state, replicaIndex, random ->
                state.insertAt(ReplicaId("R$replicaIndex"), 0, "v$replicaIndex.${random.nextInt(100)}").first
            },
            LatticeOp("insert-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val index = random.nextInt(state.size + 1)
                state.insertAt(ReplicaId("R$replicaIndex"), index, "v$replicaIndex.${random.nextInt(100)}").first
            },
            LatticeOp("remove-head", OpKind.RETIRE) { state, _, _ ->
                if (state.size > 0) state.removeAt(0)?.first ?: state else state
            },
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                if (state.size > 0) state.removeAt(random.nextInt(state.size))?.first ?: state else state
            },
        ),
        // No-op ceiling tightened from the shared 25% default. Measured over seeds `0..15` — the
        // window `generatorIsNotVacuous` runs — this binding reads **7.1%**; the ceiling sits at
        // 12%, a 4.9-point margin. See `VacuityFloors.maxNoOpSteps` for the rule and for why the
        // shared default cannot do this job. What the 12% catches that 25% did not:
        //  - the leading assert removed from the pool builder: 16.5%, reds by 4.5 points.
        //  - retirement dead off replica 0 (#2158's shape): 20.6%, reds by 8.6 points.
        floors = VacuityFloors(maxNoOpSteps = 0.12),
        serializer = Fugue.wireSerializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
