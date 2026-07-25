package us.tractat.kuilt.warp.heddle

import us.tractat.kuilt.warp.Lane
import us.tractat.kuilt.warp.TaskDescriptor

/**
 * Return a copy of this descriptor riding fair-share lane [lane] — the producer-side tagging
 * step. Everything else (op, args, trace, pin) is preserved.
 *
 * This is the shipped submission surface for lanes: build a [TaskDescriptor], tag it with
 * [inLane], and enqueue it on a [us.tractat.kuilt.warp.WarpNode] whose admission control is a
 * [HeddleAdmissionControl]. (The design's `Draft.lane("…")` modifier is the eventual dataflow
 * surface, once warp's `Draft` gains a runtime; until then the descriptor is the seam.)
 */
public fun TaskDescriptor.inLane(lane: Lane): TaskDescriptor =
    TaskDescriptor(
        op = op,
        args = args,
        traceparent = traceparent,
        pinnedOwner = pinnedOwner,
        lane = lane,
        affinity = affinity,
    )

/** Sugar for [inLane] with a string tag: `descriptor.inLane("acme/batch")`. */
public fun TaskDescriptor.inLane(tag: String): TaskDescriptor = inLane(Lane(tag))
