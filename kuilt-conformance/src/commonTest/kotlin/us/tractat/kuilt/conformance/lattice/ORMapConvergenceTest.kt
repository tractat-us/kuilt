package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * The key the retire-and-re-assert shape is built on.
 *
 * A critical shape's ops have to agree on what they touch, so the three that form it pin this key
 * rather than drawing one from `random`. The `-roam` variants keep the small random key pool that
 * forces concurrent put/remove collisions across replicas — which is what exercises add-wins.
 */
private const val FOCUS_KEY = "k-0"

private const val HIGH_COUNT = 4L
private const val LOW_COUNT = 1L

internal class ORMapConvergenceTest : LatticeLawSuite<ORMap<String, GCounter>>() {
    override fun newHarness(): LatticeLawHarness<ORMap<String, GCounter>> = LatticeLawHarness(
        initial = ORMap.empty(),
        // Declaration order is load-bearing: `defaultCriticalShapes` takes the first two ASSERT ops
        // and the first RETIRE, so this alphabet's default shape is `put-high · remove · put-low`
        // — the counterexample #2086 needs, in the only order that can see it.
        //
        // `put-low` must be the SECOND assert, not the first. A `GCounter` join takes the max per
        // author, so re-asserting 4 after retiring 1 lands on `{R0:4}` whether the retired
        // contribution survived the join or not: measured 0 violations against a lattice provably
        // broken in exactly this way. Re-asserting 1 after retiring 4 separates the two — `{R0:1}`
        // if the retirement was honoured, `{R0:4}` if it was not — and finds it on every seed.
        alphabet = listOf(
            LatticeOp("put-high", OpKind.ASSERT) { state, replicaIndex, _ ->
                val r = ReplicaId("R$replicaIndex")
                state.piece { it.put(r, FOCUS_KEY, GCounter.of(r to HIGH_COUNT)) }
            },
            LatticeOp("put-low", OpKind.ASSERT) { state, replicaIndex, _ ->
                val r = ReplicaId("R$replicaIndex")
                state.piece { it.put(r, FOCUS_KEY, GCounter.of(r to LOW_COUNT)) }
            },
            LatticeOp("put-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val r = ReplicaId("R$replicaIndex")
                state.piece { it.put(r, "k-${random.nextInt(0, 3)}", GCounter.of(r to random.nextLong(1L, 4L))) }
            },
            LatticeOp("remove", OpKind.RETIRE) { state, _, _ ->
                state.piece { it.remove(FOCUS_KEY) }
            },
            // Roams over the keys the state **actually holds**, not over the key pool. Drawing
            // from the pool spent 102 of 126 draws (81%) removing a key that was not there, which
            // absorbs the lattice identity and changes nothing — on its own it was 20% of every
            // step this binding took, and it is what put the whole binding at 27.2% no-ops against
            // Task 5's 25% ceiling. Roaming is the part worth keeping: a remove that lands on a
            // key another replica is concurrently putting is what exercises add-wins, and pinning
            // this op to `FOCUS_KEY` (the cheap way to the same number) would delete that.
            //
            // `sorted()` rather than the set's own order: `keys` is backed by a `HashMap` on JVM
            // and Android and by an insertion-ordered map on Kotlin/Native and wasmJs, so indexing
            // it raw would give the targets different trajectories from the same seed.
            //
            // The empty-state fallback keeps the absent-key case in the alphabet rather than
            // erasing it — replicas 1 and 2 start empty, and "removing an absent key is the
            // identity" is real behaviour worth reaching. `remove` (pinned) reaches it too, at
            // 40/97 draws, which is where the residual no-op rate lives.
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                val held = state.keys.sorted()
                val key = if (held.isEmpty()) "k-${random.nextInt(0, 3)}" else held[random.nextInt(held.size)]
                state.piece { it.remove(key) }
            },
        ),
        serializer = ORMap.serializer(String.serializer(), GCounter.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
