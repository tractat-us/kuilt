package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId

/**
 * Frames from one admitted [member] were routed and are gone: the reason a [Room.incomingFrom] flow
 * failed instead of delivering them (#2802).
 *
 * A per-member stream never loses a frame silently. It delivers every frame it held, in arrival order,
 * and then either **completes** — the member's admission ended, or there is no current admission to read
 * — or **fails with this**, saying how much was lost and which side of the claim it happened on.
 *
 * [dropped] is the number of frames known lost when the stream ended. [claimed] says which case it was:
 * `false` — nothing had called [Room.incomingFrom] for this admission before its inbox overflowed, so the
 * room released what it held, including the member's first frames; `true` — a collector had claimed the
 * inbox and fell behind it, so the flow delivered everything it held and then failed here rather than make
 * the room's routing wait. One type, because the consumer's answer to both is the same.
 *
 * **Terminal for the admission.** Every later collection or claim of that member's frames fails the same
 * way, and nothing restarts the stream. Treat the member as lost — let its session drop and re-join, which
 * is a new admission with a fresh inbox — rather than reading on.
 *
 * A [RuntimeException] rather than an [IllegalStateException] so that it stays distinguishable from the
 * [IllegalStateException] a second concurrent collection throws, which is a bug in the collector rather
 * than a fact about the member's frames.
 */
public class FramesLost(
    public val member: PeerId,
    public val dropped: Long,
    public val claimed: Boolean,
) : RuntimeException(
    "incomingFrom(${member.value}): $dropped frames lost — " +
        if (claimed) "the collector fell behind its inbox" else "the inbox overflowed before it was claimed",
)
