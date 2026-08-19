package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.piece

// Mixed adds and removes populate both the added and removed sets, so both fields are
// exercised for canonical encoding.
private const val FOCUS = "e0"
private const val OTHER = "e1"
private val POOL = List(6) { "e$it" }

internal class TwoPhaseSetConvergenceTest : LatticeLawSuite<TwoPhaseSet<String>>() {
    override fun newHarness(): LatticeLawHarness<TwoPhaseSet<String>> = LatticeLawHarness(
        initial = TwoPhaseSet.empty(),
        // The derived default — `add · remove · add-other` — is the right word here, and its second
        // assert has to name a DIFFERENT element rather than repeat the first. A tombstone in a
        // two-phase set is permanent, so re-adding what was just removed puts the element into a
        // set it is already in: the state does not move and the shape would assert nothing. The
        // harness would say so rather than let it pass; naming a distinct element is the fix.
        alphabet = listOf(
            LatticeOp("add", OpKind.ASSERT) { state, _, _ -> state.piece(state.add(FOCUS)) },
            LatticeOp("add-other", OpKind.ASSERT) { state, _, _ -> state.piece(state.add(OTHER)) },
            // Both roaming ops draw from what is **actionable**, not from the whole pool, and in a
            // two-phase set that is a sharper constraint than it is anywhere else: an element is
            // single-shot. Once added it can never be added again, once removed it can never come
            // back, so an op that keeps naming the same element is dead after one use rather than
            // merely wasteful. Drawing blind, this binding spent 213 of 522 exploration steps
            // (40.8%) changing nothing, the worst of the nineteen and well past Task 5's 25%
            // ceiling; the three pinned ops above are dead on replica 0 the moment the critical
            // shape has run, which leaves the roaming pair to carry the exploration.
            //
            // `add-roam` takes an element the state has neither added nor removed, so it always
            // lands; the size-derived fallback keeps it landing once the pool is exhausted rather
            // than silently becoming a no-op. Two replicas that have added the same number of
            // elements derive the same fallback, which is the cross-replica collision the fixed
            // pool was there for in the first place.
            LatticeOp("add-roam", OpKind.ASSERT) { state, _, random ->
                val addable = POOL.filter { it !in state.added && it !in state.removed }
                val element = if (addable.isEmpty()) "e${state.added.size}" else addable[random.nextInt(addable.size)]
                state.piece(state.add(element))
            },
            LatticeOp("remove", OpKind.RETIRE) { state, _, _ -> state.piece(state.remove(FOCUS)) },
            // Roams over the elements the state actually holds, so the tombstone it writes is one
            // that changes what the set reads back. `sorted()` because `elements` is `added -
            // removed`, backed by a `HashMap` on JVM and Android and by an insertion-ordered map on
            // Kotlin/Native and wasmJs — indexing it raw would give the targets different
            // trajectories from one seed. The empty-state fallback keeps "removing something never
            // added" reachable: in a two-phase set that is *not* the identity, it writes a
            // tombstone that pre-empts a later add, which is worth exercising.
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                val live = state.elements.sorted()
                val element = if (live.isEmpty()) POOL[random.nextInt(POOL.size)] else live[random.nextInt(live.size)]
                state.piece(state.remove(element))
            },
        ),
        // No-op ceiling tightened from the shared 25% default. Measured over seeds `0..15` — the
        // window `generatorIsNotVacuous` runs — this binding reads **19.3%**; the ceiling sits at
        // 22%, a 2.7-point margin. See `VacuityFloors.maxNoOpSteps` for the rule and for why the
        // shared default cannot do this job. What the 22% catches that 25% did not:
        //  - the leading assert removed from the pool builder: 23.8%, reds by 1.8 points.
        //  - retirement dead off replica 0 (#2158's shape): 35.3%, reds by 13.3 points.
        floors = VacuityFloors(maxNoOpSteps = 0.22),
        serializer = TwoPhaseSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
