package us.tractat.kuilt.warp.heddle

import us.tractat.kuilt.heddle.HeddleNode
import us.tractat.kuilt.warp.AdmissionTicket
import us.tractat.kuilt.warp.Lane
import us.tractat.kuilt.warp.OpId
import us.tractat.kuilt.warp.TaskDescriptor

/**
 * Samples for `:kuilt-warp-heddle`, compiled as part of commonTest so a signature drift breaks
 * the build rather than silently rotting the docs.
 */

/**
 * Tag a task into a fair-share lane and gate a warp node on its entitlement.
 *
 * The [heddle] node is provided by the caller (bootstrap it with
 * [us.tractat.kuilt.heddle.heddleStatic]); here we only show the wiring.
 */
@Suppress("unused")
internal fun sampleHeddleAdmissionControl(heddle: HeddleNode) {
    // 1. Build the adapter — warp's opaque AdmissionControl, backed by the fair-share ledger.
    val admission = HeddleAdmissionControl(heddle)
    // Pass it to a node:  WarpNode(selfId, seam, roster, scope, clock = …, registry = …,
    //                              admissionControl = admission)

    // 2. Tag a task into a lane on the producer side.
    val interactive: TaskDescriptor =
        TaskDescriptor(op = OpId("score"), args = "doc-1".encodeToByteArray())
            .inLane("acme/interactive")
    check(interactive.lane == Lane("acme/interactive"))

    // 3. An untagged task rides the default root lane and is admitted un-gated.
    val untagged = TaskDescriptor(op = OpId("score"), args = ByteArray(0))
    check(untagged.lane == Lane.ROOT)
    check(admission.admit(untagged) === AdmissionTicket.NOOP)
}
