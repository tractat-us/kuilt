package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.ReplicaId

/** Matches the harness's `replicaCount` below — the ids `witness-peer` may reach for. */
private const val REPLICA_COUNT = 3

/**
 * How far past the contiguous prefix `witness-gap` may jump, mirroring the seq range
 * `DotContextLawsPropertyTest.trajectories` drew from.
 */
private const val GAP_SPREAD = 3

/** Seq range `witness-peer` starts its scan in — `DotContextLawsPropertyTest` used `1..5`. */
private const val PEER_SEQ_SPREAD = 5

/**
 * `DotContext` — the causal history every other causal CRDT is adjudicated by, bound on its own.
 *
 * **This binding is grow-only, and says so by declaring no [OpKind.RETIRE] op.** A `DotContext` only
 * ever witnesses more; there is no operation that withdraws a dot, and inventing one to satisfy the
 * default critical shape would be a lie about the type. [defaultCriticalShapes] therefore derives an
 * empty list, exactly as it does for `GSet` and the counters, and the retirement dimension is simply
 * absent here rather than faked.
 *
 * **What the pool has to reach instead is the cloud.** `DotContext` stores a contiguous prefix per
 * replica as a version vector and parks anything non-contiguous in a cloud until the gap before it
 * fills, at which point compaction pulls it into the vector. Compaction is a fixpoint loop, which is
 * where associativity is genuinely at risk: a dot parked in `c`'s cloud must compact the same way
 * whether `c` arrives alone or already joined onto `b`. So the alphabet's job is to manufacture gaps
 * and then fill them — which is what `DotContextLawsPropertyTest.trajectories` was doing when it drew
 * seqs uniformly from `1..5` for a random one of three replicas and called that "occasionally
 * introducing gaps so the cloud path is also exercised". Every op below is guaranteed to move the
 * state, so no step is spent re-witnessing something already witnessed.
 */
internal class DotContextConvergenceTest : CrdtConvergenceSuite<DotContext>() {
    override fun newHarness(): CrdtConvergenceHarness<DotContext> = CrdtConvergenceHarness(
        initial = DotContext.EMPTY,
        alphabet = listOf(
            // The contiguous step: seq = vv + 1, so it extends the vector AND cascades through any
            // cloud dots waiting at vv + 2, vv + 3, … This is the gap-FILLING op.
            LatticeOp("witness-next", OpKind.ASSERT) { state, replicaIndex, _ ->
                state.add(state.nextDot(replicaOf(replicaIndex)))
            },
            // The non-contiguous step: at least vv + 2, so it cannot compact and must park in the
            // cloud. This is the gap-MAKING op, and the pair is what gets a non-empty cloud into the
            // pool on every seed rather than on the seeds where a uniform draw happened to skip one.
            LatticeOp("witness-gap", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = replicaOf(replicaIndex)
                val start = state.nextDot(replica).seq + 1L + random.nextInt(GAP_SPREAD)
                state.add(Dot(replica, unwitnessedSeq(state, replica, start)))
            },
            // A dot belonging to somebody else. `causalPool`'s occasional gossip already unions
            // peer histories in, but only along whole states; this reaches a peer's seq range
            // directly, so one replica's cloud can hold a gap another replica's vector later fills.
            // Sound because a `DotContext` carries no payload keyed by a dot — witnessing is a set
            // union, and the same dot witnessed twice is the same history.
            LatticeOp("witness-peer", OpKind.ASSERT) { state, replicaIndex, random ->
                val peer = replicaOf((replicaIndex + 1 + random.nextInt(REPLICA_COUNT - 1)) % REPLICA_COUNT)
                val start = 1L + random.nextInt(PEER_SEQ_SPREAD)
                state.add(Dot(peer, unwitnessedSeq(state, peer, start)))
            },
        ),
        // A `DotContext` records the dots it has witnessed and never un-witnesses one — compaction
        // moves a dot from the cloud into the vector, which is a change of representation and not a
        // withdrawal. So there is no RETIRE op to declare, as the alphabet above already says.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = DotContext.serializer(),
        replicaCount = REPLICA_COUNT,
        opsPerReplica = 8,
    )
}

private fun replicaOf(index: Int): ReplicaId = ReplicaId("R$index")

/**
 * The lowest seq at or above [start] that [context] has not witnessed for [replica].
 *
 * Every op is required to move the state — a re-witnessed dot is a no-op, and a pool slot spent on
 * one buys nothing. The scan terminates after at most as many steps as ops applied so far, since
 * each op witnesses exactly one dot.
 */
private fun unwitnessedSeq(context: DotContext, replica: ReplicaId, start: Long): Long {
    var seq = start
    while (context.contains(Dot(replica, seq))) seq++
    return seq
}
