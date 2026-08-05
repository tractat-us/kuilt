package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the **mechanism** behind #2077 — why the read half of
 * [WarpNode.enqueue] `(taskId, CoordinationKind.Coordinated)` must be inside the lock, not just
 * the write half.
 *
 * The coordinated queue is an [ORSet], a dot-based CRDT. `add` mints its dot from the *context of
 * the state it is called on*, so two adds derived from the **same** snapshot under the **same**
 * replica mint the **same** dot. Joining those two patches does not pick a winner: each patch
 * witnesses the shared dot in its context while carrying only its own key, so the join reads the
 * other side's key as retired and **both** additions are annihilated.
 *
 * Deterministic by construction — no threads, no scheduler — so it can never go flaky. It is the
 * companion to [WarpNodeCoordinatedEnqueueConcurrencyTest], which drives the same hazard through
 * real threads on a live [WarpNode].
 */
class CoordinatedQueueDotUniquenessTest {

    private val replica = ReplicaId("node-a")
    private val alpha = TaskId("alpha")
    private val beta = TaskId("beta")

    /**
     * The hazard: two patches minted from one captured snapshot under one replica reuse a dot,
     * and the causal join annihilates **both** additions — not last-writer-wins, *neither*
     * writer wins.
     */
    @Test
    fun sameSnapshotAddsReuseADotAndAnnihilateEachOther() {
        val snapshot = ORSet.empty<TaskId>()

        val first = snapshot.piece { it.add(replica, alpha) }
        val second = snapshot.piece { it.add(replica, beta) }

        assertAll(
            { assertEquals(setOf(alpha), first.elements, "each patch alone carries its own task") },
            { assertEquals(setOf(beta), second.elements, "each patch alone carries its own task") },
            { assertEquals(emptySet(), first.piece(second).elements, "duplicate dots annihilate both tasks") },
            { assertEquals(emptySet(), second.piece(first).elements, "annihilation is symmetric — join order cannot save either task") },
        )
    }

    /**
     * The invariant the lock buys: when the second add is minted from the state the first
     * produced — i.e. the read and the write are one atomic step — the dots are distinct and both
     * tasks survive every join order.
     */
    @Test
    fun serialisedAddsMintDistinctDotsAndBothSurvive() {
        val snapshot = ORSet.empty<TaskId>()

        val first = snapshot.piece { it.add(replica, alpha) }
        val second = first.piece { it.add(replica, beta) }

        assertAll(
            { assertEquals(setOf(alpha, beta), second.elements, "serialised adds accumulate") },
            { assertEquals(setOf(alpha, beta), first.piece(second).elements, "both tasks survive the join") },
            { assertEquals(setOf(alpha, beta), second.piece(first).elements, "join order is irrelevant once dots are distinct") },
        )
    }
}
