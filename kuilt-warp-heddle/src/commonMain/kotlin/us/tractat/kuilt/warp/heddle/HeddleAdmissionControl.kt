package us.tractat.kuilt.warp.heddle

import us.tractat.kuilt.heddle.FairShareExecution
import us.tractat.kuilt.heddle.GroupId
import us.tractat.kuilt.heddle.HeddleNode
import us.tractat.kuilt.warp.AdmissionControl
import us.tractat.kuilt.warp.AdmissionTicket
import us.tractat.kuilt.warp.Lane
import us.tractat.kuilt.warp.TaskDescriptor
import us.tractat.kuilt.warp.WarpNode

/**
 * Binds warp's opaque [Lane] tags to a weighted fair-share tree and gates warp's free
 * execution path on entitlement — the whole point of the `:kuilt-warp-heddle` satellite.
 *
 * Warp answers *where* a task runs (the consistent-hash ring) and *whose turn* (the free
 * claim path). It has no notion of *how much* any lane may take. This adapter supplies that
 * missing dimension without warp core learning a single fair-share type: it implements warp's
 * opaque [AdmissionControl], and warp calls it just before running each task. The adapter maps
 * the task's [Lane] to a fair-share **leaf** and [reserves][FairShareExecution.reserve] one task's
 * worth of that leaf's entitlement; when the task finishes warp calls [AdmissionTicket.settle],
 * which [completes][FairShareExecution.complete] the reservation and charges the ledger exactly once.
 *
 * The behaviour that falls out, per warp's contract:
 *  - **Untagged is free.** A task on the [Lane.ROOT] lane (the default) is admitted immediately
 *    with a no-op ticket — no reservation, no ledger touch — so an untagged workload is
 *    bit-for-bit today's warp.
 *  - **Exhaustion defers, never drops.** When a lane's leaf has no spare entitlement,
 *    [FairShareExecution.reserve] returns `null`; this adapter returns `null` too, so warp *defers*
 *    the task (leaves it pending) and re-attempts it on a later claim cycle — work resumes when
 *    entitlement flows in.
 *  - **Zero consensus on the hot path.** [reserve] / [complete] are local reads and writes of
 *    already-converged holdings; admitting a task adds no consensus round. The heddle's own
 *    ledger replication is coordination-free cloth (a `Quilter`), not consensus.
 *
 * Entitlement itself flows the ordinary heddle way — a consumer advertises demand and calls
 * [HeddleNode.schedule] to delegate holdings down the tree by weight. Two lanes weighted `3:1`
 * therefore complete tasks in a `3:1` ratio: each lane runs exactly as many tasks as it was
 * delegated entitlement for.
 *
 * @param heddle the fair-share data plane whose holdings this adapter reserves against — the
 *   [FairShareExecution] surface shared by both front doors, so this accepts either a
 *   [us.tractat.kuilt.heddle.heddleStatic] node (no consensus) or an H5
 *   [us.tractat.kuilt.heddle.heddleGoverned] node (governed) interchangeably.
 * @param costPerTask service units reserved and charged per task. Defaults to `1` — the §14.4
 *   "one unit per task" costing; a caller with variable-cost work supplies a per-descriptor cost
 *   via [costOf].
 * @param costOf per-task cost function; defaults to a flat [costPerTask] for every descriptor.
 * @param laneToLeaf maps a task's [Lane] to the fair-share leaf [GroupId] to charge, or `null`
 *   to admit the task un-gated. The default treats [Lane.ROOT] as un-gated and every other tag
 *   as the identically-named leaf (`Lane("acme/batch") → GroupId("acme/batch")`).
 *
 * @sample us.tractat.kuilt.warp.heddle.sampleHeddleAdmissionControl
 * @see WarpNode
 */
public class HeddleAdmissionControl(
    private val heddle: FairShareExecution,
    private val costPerTask: Long = 1L,
    private val costOf: (TaskDescriptor) -> Long = { costPerTask },
    private val laneToLeaf: (Lane) -> GroupId? = { lane -> defaultLeafOf(lane) },
) : AdmissionControl {

    /**
     * Reserve this task's lane entitlement, or defer it.
     *
     * Returns a settling [AdmissionTicket] when the reservation succeeds; `null` (defer) when the
     * lane's leaf is out of entitlement. An un-gated lane (`laneToLeaf` returns `null`, e.g. the
     * default [Lane.ROOT]) is admitted immediately with a no-op ticket, touching no ledger.
     */
    override fun admit(descriptor: TaskDescriptor): AdmissionTicket? {
        val leaf = laneToLeaf(descriptor.lane) ?: return AdmissionTicket.NOOP
        val cost = costOf(descriptor)
        val reservation = heddle.reserve(leaf, cost) ?: return null // lane exhausted → defer
        return AdmissionTicket { heddle.complete(reservation, cost) }
    }

    public companion object {
        /**
         * The default [Lane] → leaf binding: the [Lane.ROOT] (no-lane) default is un-gated
         * (`null`); every other tag names the identically-named leaf group.
         */
        public fun defaultLeafOf(lane: Lane): GroupId? =
            if (lane == Lane.ROOT) null else GroupId(lane.tag)
    }
}
