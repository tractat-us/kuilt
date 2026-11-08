package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.CausalMap
import us.tractat.kuilt.crdt.DotFun
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

private typealias Doc = CausalMap<String, DotMap<String, DotFun<Int>>>

// Two outer keys and two fields, small enough that replicas remove and recreate the same key
// constantly: the shape #3126 is about. The pinned ops agree on FOCUS so the critical shape's three
// steps land on one key.
private val OUTERS = listOf("poll", "note")
private val FIELDS = listOf("question", "votes")
private val FOCUS = OUTERS[0]

/**
 * `CausalMap<String, DotMap<String, DotFun<Int>>>` — MV registers two levels deep under one shared
 * context, driven only through `apply` and `remove`.
 *
 * **What this binding can and cannot red.** The join is `Causal<DotMap<…>>`'s, which
 * `CausalDotMapConvergenceTest` already binds, and #3126 never broke a lattice law: the bug was a
 * lost put. So this is a regression guard for the facade — that every delta `apply` and `remove`
 * produce is a lattice element that converges, at depth 2, with remove and recreate of one key
 * racing on three replicas. The evidence for #3126 itself is `CausalMapTest` in `:kuilt-crdt`.
 *
 * Every pick is drawn from a sorted list, so the trajectory is a function of the seed on every
 * target.
 */
internal class CausalMapConvergenceTest : LatticeLawSuite<Doc>() {
    override fun newHarness(): LatticeLawHarness<Doc> = LatticeLawHarness(
        initial = CausalMap.empty(),
        alphabet = listOf(
            LatticeOp("write", OpKind.ASSERT) { state, replicaIndex, random ->
                write(state, ReplicaId("R$replicaIndex"), FOCUS, FIELDS[random.nextInt(FIELDS.size)], random.nextInt(10))
            },
            LatticeOp("write-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val outer = OUTERS[random.nextInt(OUTERS.size)]
                write(state, ReplicaId("R$replicaIndex"), outer, FIELDS[random.nextInt(FIELDS.size)], random.nextInt(10))
            },
            // Removes FOCUS, which the critical shape needs; when FOCUS is absent it removes the
            // first key held instead, so the step is not spent on a no-op.
            LatticeOp("remove", OpKind.RETIRE) { state, _, _ ->
                val target = if (FOCUS in state.keys) FOCUS else state.keys.sorted().firstOrNull()
                if (target == null) state else state.piece(state.remove(target))
            },
            // Removes a field the state holds, never an absent one, which would be a no-op step.
            LatticeOp("remove-field", OpKind.RETIRE) { state, replicaIndex, random ->
                val held = state.keys.sorted().flatMap { outer ->
                    state[outer]?.entries?.keys.orEmpty().sorted().map { outer to it }
                }
                if (held.isEmpty()) {
                    state
                } else {
                    val (outer, field) = held[random.nextInt(held.size)]
                    removeField(state, ReplicaId("R$replicaIndex"), outer, field)
                }
            },
        ),
        // Remove and recreate one key: under a per-value context the recreated write reissued the
        // dot the remove had retired.
        criticalShapes = listOf(listOf("write", "remove", "write")),
        serializer = CausalMap.serializer(
            String.serializer(),
            DotMap.serializer(String.serializer(), DotFun.serializer(Int.serializer())),
        ),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}

/** An MV write at `/[outer]/[field]`: one fresh dot, retiring what the register held. */
private fun write(state: Doc, replica: ReplicaId, outer: String, field: String, value: Int): Doc =
    state.piece(
        state.apply(replica, outer, DotMap()) { slot ->
            val register = slot.narrow(slot.store.entries[field] ?: DotFun())
            CausalMap.Change(
                DotMap(mapOf(field to DotFun(mapOf(register.mint() to value)))),
                retired = register.store.dots,
            )
        },
    )

/** Remove `/[outer]/[field]`, retiring every dot it held. */
private fun removeField(state: Doc, replica: ReplicaId, outer: String, field: String): Doc =
    state.piece(
        state.apply(replica, outer, DotMap()) { slot ->
            CausalMap.Change(DotMap(), retired = slot.store.entries[field]?.dots.orEmpty())
        },
    )
