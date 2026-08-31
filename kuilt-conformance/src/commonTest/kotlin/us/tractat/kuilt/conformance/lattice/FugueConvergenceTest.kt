package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.ReplicaId

// Random-position inserts plus removes force concurrent same-anchor siblings and
// tombstone merges — the patterns that reveal Fugue tree-ordering (non-interleaving) bugs.
internal class FugueConvergenceTest : CompactableLatticeLawSuite<Fugue<String>>() {
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
        // 16, not the shared default of 8, and the only generator knob this change moves.
        // Measured over seeds 0..31: pre-merge runs with >=2 replicas compacting go 13/32 -> 24/32
        // and post-merge runs 27/32 -> 31/32. Strictly more search — more ops can only add
        // trajectories — but it does move an existing load-bearing test's shape, so the #1978
        // mutation was re-run at 16 and is still red (see the PR's matrix).
        // It does NOT move the causal pool the associativity/codec/vacuity passes walk: that
        // builder stops at POOL_LIMIT = 14, which this binding reaches well inside 8 ops.
        opsPerReplica = 16,
        // The `Compact.positions` map, the identical #1978 mechanism at the identical position as
        // `Rga`'s. `Fugue` additionally has the #713 axis — the order *between* several `Compact`
        // ops in one log — which only the pre-merge phase reaches.
        compactor = { state, stableCut, frontierMax, delivered ->
            state.compact(stableCut, frontierMax, delivered)
                ?.let { (compacted, op) -> CompactionStep(compacted, op.positions.size) }
        },
    )

    // Measured over seeds 0..31 on 2026-08-30, at opsPerReplica = 16: post-merge 31/32, max
    // dropped in one step 5, pre-merge >=2 replicas 24/32. Floors at ~three-quarters of each.
    override val compactionFloors: CompactionFloors = CompactionFloors(
        postMergeRunsWithCompaction = 23,
        postMergeMaxDroppedInOneStep = 4,
        preMergeRunsWithTwoOrMoreCompacting = 18,
    )
}
