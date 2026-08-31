package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga

// Random-position inserts plus removes force concurrent same-predecessor siblings and
// tombstone merges — exactly the patterns that reveal ordering-tiebreak bugs.
internal class RgaConvergenceTest : CompactableLatticeLawSuite<Rga<String>>() {
    override fun newHarness(): LatticeLawHarness<Rga<String>> = LatticeLawHarness(
        initial = Rga.empty(),
        // `insert-head` / `remove-head` both pin index 0, so the derived shape
        // `insert-head · remove-head · insert-roam` retires exactly the element it just inserted.
        // The roaming pair keeps the random-position behaviour that forces concurrent
        // same-predecessor siblings.
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
        serializer = Rga.wireSerializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
        // Reaches `compact()` at the harness-derived cut. The `Compact.positions` map is the
        // #1978 field: derived from `tombstones`, which `piece` builds with `Set.plus` — a
        // `LinkedHashSet` in merge order — so its key order is a function of the fold, and the
        // post-merge phase is what varies the fold.
        compactor = { state, stableCut, frontierMax, delivered ->
            state.compact(stableCut, frontierMax, delivered)
                ?.let { (compacted, op) -> CompactionStep(compacted, op.positions.size) }
        },
    )

    // Measured over seeds 0..31 on 2026-08-30, at this binding's own opsPerReplica = 8:
    // post-merge 32/32, max dropped in one step 8, pre-merge >=2 replicas 32/32. Floors at
    // ~three-quarters of each, so a drift is visible as a drift rather than as a pass.
    override val compactionFloors: CompactionFloors = CompactionFloors(
        postMergeRunsWithCompaction = 24,
        postMergeMaxDroppedInOneStep = 6,
        preMergeRunsWithTwoOrMoreCompacting = 24,
    )
}
