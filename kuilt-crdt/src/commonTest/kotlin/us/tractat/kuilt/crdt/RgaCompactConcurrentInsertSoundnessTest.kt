package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the soundness gap in the RGA GC watermark bridge (refs #247, #258).
 *
 * ## The hazard
 *
 * `RgaGcCoordinator` derives its GC watermark from
 * `SeamReplicator.universalAckFlow`, which tracks
 * `min over peers of ackedThrough[peer]` where `ackedThrough[peer]` is the
 * highest seq of **this replica's own** deltas that the peer has acked. That is
 * **per-author delta-ack**, not **global causal stability**: it tells A that
 * every peer has absorbed A's own delta stream up to seq N — it says nothing
 * about whether those peers' *other* (concurrent) ops have reached A.
 *
 * `Rga.compact(watermark)` then GCs any tombstoned op with `lamport ≤ watermark`
 * subject to one structural check — condition 2: "no surviving op has
 * `after == id`". That check is evaluated against **A's local op-log only**. If a
 * concurrent `Insert(J, after = I)` exists on another peer but has not yet been
 * delivered to A, `I` looks like a safe leaf on A and is GC'd.
 *
 * ## What this test demonstrates
 *
 * Peer A tombstones `I` and GCs it (broadcasting `Compact({I})`). Concurrently,
 * peer C authored `Insert(J, after = I)` that A had not yet received. After full
 * propagation (A learns `J`; C learns `Compact({I})`), `J`'s structural
 * predecessor `I` is purged everywhere, so `J` is orphaned and **silently and
 * permanently dropped**.
 *
 * Convergence (eventual consistency) is NOT violated — the `Compact` op is a
 * retained, set-union-mergeable op, so every replica purges the same id set and
 * orphans the same `J`. The violation is weaker but real: a committed insert is
 * lost. This is the invariant a correct GC must preserve, so the assertion below
 * states the desired behaviour (`J` survives) and currently FAILS, pinning the
 * bug.
 *
 * Root cause: **per-replica delta-ack ≠ global causal stability.** The watermark
 * does not prove that every peer's concurrent ops up to `I.lamport` have been
 * delivered to the compacting replica, which is what condition 2 silently
 * assumes.
 */
class RgaCompactConcurrentInsertSoundnessTest {

    private val a = ReplicaId("a")
    private val c = ReplicaId("c")

    /**
     * A concurrent `Insert(J, after = I)` must not be lost when peer A GCs the
     * tombstoned `I` before receiving `J`. Currently it is — this assertion
     * fails, pinning the soundness gap.
     */
    @Test
    fun concurrentInsertSurvivesGcOfItsTombstonedPredecessor() {
        // Shared prefix: A inserts I, then tombstones it. C has the same op-log.
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)

        // Concurrency: C inserts J after I; A has NOT seen this op yet.
        val (cWithJ, opJ) = cBase.insertAfter(c, opI.id, "J")

        // A GCs I at a watermark that covers I's lamport — the per-author-ack
        // watermark legitimately reaches here without any peer having proven it
        // delivered J. On A's local op-log, I has no surviving successor, so
        // condition 2 passes and I is purged.
        val (aCompacted, compactOp) = aTombstoned.compact(watermark = opI.id.lamport)!!

        // Full propagation: A learns J; C learns Compact({I}).
        val aFinal = aCompacted.apply(opJ)
        val cFinal = cWithJ.apply(compactOp)

        // Eventual consistency holds — replicas converge. (Sanity guard.)
        assertEquals(aFinal, cFinal, "replicas must converge (they do — convergence is not the bug)")

        // The actual invariant: J — a committed insert — must survive.
        assertEquals(
            listOf("J"),
            aFinal.toList(),
            "concurrent Insert(J, after=I) must not be silently lost when I is GC'd",
        )
    }

    /**
     * Transient anomaly: during the window after C inserts `J` but before
     * `Compact({I})` reaches C, C shows `[J]` while A shows `[]`. This documents
     * the observable divergence window (it heals on convergence, but it is a real
     * user-visible flap of committed content).
     */
    @Test
    fun concurrentInsertIsTransientlyVisibleOnAuthorBeforeCompactArrives() {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (aTombstoned, remI) = a0.removeAt(0)!!
        val cBase = Rga.empty<String>().apply(opI).apply(remI)
        val (cWithJ, _) = cBase.insertAfter(c, opI.id, "J")
        val (aCompacted, _) = aTombstoned.compact(watermark = opI.id.lamport)!!

        // Pre-convergence snapshot: author C sees J; compactor A does not.
        assertEquals(listOf("J"), cWithJ.toList(), "C (author) sees J before Compact arrives")
        assertEquals(emptyList(), aCompacted.toList(), "A has already dropped I and never saw J")
    }
}
