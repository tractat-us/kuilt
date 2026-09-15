package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId

/**
 * Why a [Room.incomingFrom] flow failed instead of delivering one member's frames (#2802).
 *
 * A per-member stream never loses a frame silently. It delivers every frame it held, in arrival order,
 * and then either **completes** — the member's admission ended — or **fails** with one of these, saying
 * why and how much.
 *
 * **A failure is terminal for the admission.** Every later collection or claim of that member's frames
 * fails the same way, and nothing restarts the stream from now: the frames are gone and so is the
 * guarantee. Treat the member as lost — evict it, or let its session drop and re-join, which is a new
 * admission with a fresh inbox — rather than reading on.
 *
 * A [RuntimeException] rather than an [IllegalStateException] so that it stays distinguishable from the
 * [IllegalStateException] a second concurrent collection throws, which is a bug in the collector rather
 * than a fact about the member's frames.
 */
public sealed class MemberInboxException(message: String) : RuntimeException(message) {
    /** The member whose frames this is about. */
    public abstract val member: PeerId

    /**
     * [Room.incomingFrom] was called for a peer with no current admission — never admitted, already
     * evicted, or the room is terminal. An inbox is per admission, so there is nothing to read.
     */
    public class NotAdmitted(override val member: PeerId) :
        MemberInboxException("incomingFrom(${member.value}): no current admission for this peer, so no frames are held")

    /**
     * Nothing called [Room.incomingFrom] for this admission before the inbox overflowed, so the room
     * released what it held and stopped holding. [dropped] frames from [member] were routed and are gone,
     * including the member's first frames.
     */
    public class ReleasedBeforeClaim(
        override val member: PeerId,
        public val dropped: Long,
    ) : MemberInboxException(
        "incomingFrom(${member.value}): $dropped frames were not held — the inbox overflowed before it was claimed",
    )

    /**
     * The claimed collector fell [capacity] frames behind. The flow delivered every frame it held and
     * then failed here rather than make the room's routing wait: every later frame from [member] is lost.
     */
    public class CollectorFellBehind(
        override val member: PeerId,
        public val capacity: Int,
    ) : MemberInboxException(
        "incomingFrom(${member.value}): the collector fell $capacity frames behind; later frames were dropped",
    )
}
