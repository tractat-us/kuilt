package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LatticeDotFun
import us.tractat.kuilt.crdt.ReplicaId

private typealias Counters = Causal<LatticeDotFun<GCounter>>

/**
 * `Causal<LatticeDotFun<GCounter>>` — a dot store whose payloads grow after they are minted.
 *
 * The op that matters is `bump`: it grows the payload of a dot that is **already live**, on
 * whichever replica draws it, so two replicas holding one dot routinely hold two different payloads
 * for it. That is the state `DotFun`'s join cannot meet commutatively, and the reason this type
 * exists. A generator without `bump` would only ever mint fresh dots and could not tell the two
 * joins apart.
 *
 * `bump` picks from the dots the state holds, sorted, so the pick is a function of the seed and not
 * of hash order, which differs between targets (`forbidHashOrderedSeededDraw`).
 */
internal class LatticeDotFunConvergenceTest : LatticeLawSuite<Counters>() {
    override fun newHarness(): LatticeLawHarness<Counters> = LatticeLawHarness(
        initial = Causal(LatticeDotFun(), DotContext.EMPTY),
        alphabet = listOf(
            LatticeOp("write", OpKind.ASSERT) { state, replicaIndex, _ ->
                write(state, ReplicaId("R$replicaIndex"))
            },
            // With nothing to bump it writes instead, so it is an assert on every state, the lattice
            // bottom included (the harness leads every replica with one, #2145).
            LatticeOp("bump", OpKind.ASSERT) { state, replicaIndex, random ->
                val held = state.store.dots.sorted()
                if (held.isEmpty()) {
                    write(state, ReplicaId("R$replicaIndex"))
                } else {
                    bump(state, held[random.nextInt(held.size)], ReplicaId("R$replicaIndex"), random.nextLong(1L, 4L))
                }
            },
            // Removes a dot the state holds, never an absent one: a remove of nothing is a no-op
            // step and spends a pool slot on nothing.
            LatticeOp("remove", OpKind.RETIRE) { state, _, random ->
                val held = state.store.dots.sorted()
                if (held.isEmpty()) state else remove(state, held[random.nextInt(held.size)])
            },
        ),
        // A dot bumped and then removed must stay removed when a concurrent bump of it arrives.
        criticalShapes = listOf(listOf("write", "bump", "remove", "write")),
        serializer = Causal.serializer(LatticeDotFun.serializer(GCounter.serializer())),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}

/** Mint [replica]'s next dot with a one-count payload, witnessing it. */
private fun write(state: Counters, replica: ReplicaId): Counters {
    val dot = state.context.nextDot(replica)
    return Causal(
        LatticeDotFun(state.store.values + (dot to GCounter.of(replica to 1L))),
        state.context.add(dot),
    )
}

/** Grow [dot]'s payload by [replica]'s slot. The dot is unchanged; only its payload moves up. */
private fun bump(state: Counters, dot: Dot, replica: ReplicaId, by: Long): Counters {
    val payload = state.store.values.getValue(dot)
    return Causal(
        LatticeDotFun(state.store.values + (dot to payload.piece(payload.inc(replica, by).delta))),
        state.context,
    )
}

/** Drop [dot] from the store and keep it witnessed, so a merge can tell removed from unseen. */
private fun remove(state: Counters, dot: Dot): Counters =
    Causal(LatticeDotFun(state.store.values - dot), state.context)
