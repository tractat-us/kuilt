package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ReplicaId

// A four-key pool, small enough that replicas collide on keys constantly — the shape that makes
// `DotMap.join`'s per-key recursion do work rather than merge disjoint halves. The `-roam` ops draw
// from it; the pinned ops agree on FOCUS so the critical shape's three steps touch the same key.
private val KEYS = listOf("k-0", "k-1", "k-2", "k-3")
private val FOCUS = KEYS[0]

/**
 * `Causal<DotMap<String, DotSet>>` — the shape underlying `ORSet`, bound directly so the nesting is
 * exercised without `ORSet`'s facade in the way.
 *
 * The alphabet preserves what `CausalDotMapLawsPropertyTest.trajectoryFor` drew: a `(key, isAdd)`
 * pair over four keys, adding a fresh dot to the key's nested `DotSet` on `true` and dropping the
 * whole key while keeping the context on `false`. What is added is a target-pinned pair of ops, so
 * the assert · retire · re-assert word lands on one key on every seed instead of on a lucky one.
 */
internal class CausalDotMapConvergenceTest : CrdtConvergenceSuite<Causal<DotMap<String, DotSet>>>() {
    override fun newHarness(): CrdtConvergenceHarness<Causal<DotMap<String, DotSet>>> = CrdtConvergenceHarness(
        initial = Causal(DotMap(), DotContext.EMPTY),
        alphabet = listOf(
            LatticeOp("add", OpKind.ASSERT) { state, replicaIndex, _ ->
                addTo(state, FOCUS, replicaIndex)
            },
            LatticeOp("add-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                addTo(state, KEYS[random.nextInt(KEYS.size)], replicaIndex)
            },
            LatticeOp("remove", OpKind.RETIRE) { state, _, _ ->
                removeFrom(state, FOCUS)
            },
            // Removes a key the state ACTUALLY HOLDS rather than one drawn from the whole pool.
            // A remove of an absent key is a no-op, and a no-op step spends a pool slot on nothing:
            // `ORMapConvergenceTest`'s generator burns 10 of its 29 steps exactly that way. Keys are
            // sorted so the pick is a function of the seed and not of map iteration order, which
            // differs between the JVM and the other targets.
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                val held = state.store.entries.keys.sorted()
                if (held.isEmpty()) state else removeFrom(state, held[random.nextInt(held.size)])
            },
        ),
        // Declared rather than derived: the default would re-assert with `add-roam`, whose key comes
        // from `random` and so need not land back on what `remove` retired. Re-adding the SAME key
        // is the shape that matters — `add` mints a fresh dot, so a lattice that let the retired dot
        // survive keeps `k-0 → {d1, d2}` where a correct one keeps `k-0 → {d2}`.
        criticalShapes = listOf(listOf("add", "remove", "add")),
        serializer = Causal.serializer(DotMap.serializer(String.serializer(), DotSet.serializer())),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}

/** Mint [replicaIndex]'s next dot and union it into [key]'s nested store, witnessing it. */
private fun addTo(
    state: Causal<DotMap<String, DotSet>>,
    key: String,
    replicaIndex: Int,
): Causal<DotMap<String, DotSet>> {
    val dot = state.context.nextDot(ReplicaId("R$replicaIndex"))
    val existing = state.store.entries[key]?.dots ?: emptySet()
    return Causal(
        DotMap(state.store.entries + (key to DotSet(existing + dot))),
        state.context.add(dot),
    )
}

/** Drop [key] entirely; leave the context alone, so the merge can still tell dropped from unseen. */
private fun removeFrom(state: Causal<DotMap<String, DotSet>>, key: String): Causal<DotMap<String, DotSet>> =
    Causal(DotMap(state.store.entries - key), state.context)
