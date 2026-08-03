package us.tractat.kuilt.quilter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector

/**
 * Wire messages exchanged between [Quilter] instances.
 *
 * [Delta] carries a lattice fragment tagged with the sender's monotonic sequence
 * number. [Ack] tells the sender that the acker has absorbed all deltas through
 * [seq], enabling GC of the delta buffer. [FullState] ships the entire current
 * state and is sent once on first contact with a new peer.
 *
 * @param S the [us.tractat.kuilt.crdt.Quilted] state type.
 */
@Serializable
public sealed class QuiltMessage<S> {

    /**
     * A lattice delta broadcast by [sender], tagged with [sender]'s
     * monotonic per-seam sequence number.
     */
    @Serializable
    @SerialName("delta")
    public class Delta<S>(
        public val sender: ReplicaId,
        public val seq: Long,
        public val delta: S,
    ) : QuiltMessage<S>()

    /**
     * Acknowledgement: [acker] has absorbed all deltas from [sender] through
     * [seq], so [sender] may GC every pending delta with seq ≤ [seq].
     */
    @Serializable
    @SerialName("ack")
    public class Ack<S>(
        public val acker: ReplicaId,
        public val sender: ReplicaId,
        public val seq: Long,
    ) : QuiltMessage<S>()

    /**
     * The complete current state — sent once on first contact with a peer that
     * has never been seen before. The recipient absorbs it with [us.tractat.kuilt.crdt.Quilted.piece].
     *
     * [upThrough] is [sender]'s own-delta high-water: the snapshot already reflects every
     * delta [sender] has minted with `seq <= upThrough` (`0` when it has minted none).
     * The recipient fast-forwards its per-sender receive cursor past that history, drops
     * buffered inbound deltas the snapshot covers, and acks [upThrough] — without this a
     * receiver whose gap outlives the sender's GC (e.g. a late joiner) can never ack via
     * the delta path, permanently pinning the sender's pending-delta buffer.
     */
    @Serializable
    @SerialName("fullState")
    public class FullState<S>(
        public val sender: ReplicaId,
        public val state: S,
        public val upThrough: Long = 0L,
    ) : QuiltMessage<S>()

    /**
     * A hash of [sender]'s whole state, sent on the anti-entropy tick in place of the state
     * itself (#1955). The recipient compares it with its own root and replies with a
     * [FullStateRequest] only if they differ, so a converged round costs one small frame
     * instead of the entire CRDT.
     *
     * [upThrough] carries the same own-delta high-water as [FullState.upThrough], and for the
     * same reason: an anti-entropy round must resync the recipient's receive cursor whether or
     * not it ships state. Omitting it here would reintroduce the #1266 livelock for a peer
     * whose state matches while its delta cursor lags.
     *
     * [upThrough] deliberately has **no default**: omitting it must be a compile error, not a
     * silent `0L` that disables the resync. ([FullState.upThrough] carries a default only because
     * it predates its callers.)
     *
     * [root] is advisory, and a collision costs more than a missed heal: the recipient takes the
     * **match** branch, so it also fast-forwards its receive cursor to [upThrough], acks it, and
     * drops buffered inbound deltas at or below it — the exact harm the mismatch branch refuses to
     * risk, inflicted here on a false match. It stays recoverable: the next state mutation on
     * either side changes both roots, and any [FullState] — from a third peer, the first-contact
     * path, or a later round whose roots then differ — re-merges the state and re-resyncs the
     * cursor. So a stall until the next round, never divergence that survives a [FullState].
     */
    @Serializable
    @SerialName("rootDigest")
    public class RootDigest<S>(
        public val sender: ReplicaId,
        public val root: Long,
        public val upThrough: Long,
    ) : QuiltMessage<S>()

    /**
     * Sent by [requester] when a [RootDigest] from [sender] disagreed with its own root: please
     * ship the state. The recipient answers with a [FullState].
     *
     * Honored only when the recipient has sent [requester] a [RootDigest] since the last request
     * it honored, which caps delivery at one full state per peer per anti-entropy interval —
     * exactly the pre-#1955 ceiling — and makes an unsolicited request a no-op.
     */
    @Serializable
    @SerialName("fullStateRequest")
    public class FullStateRequest<S>(
        public val requester: ReplicaId,
        public val sender: ReplicaId,
    ) : QuiltMessage<S>()

    /**
     * Gap retransmission request: [requester] has detected that it is missing
     * deltas from [sender] in the range [[fromSeq]..[toSeq]] inclusive.
     * [sender] should re-broadcast each buffered delta in that range.
     */
    @Serializable
    @SerialName("resend")
    public class Resend<S>(
        public val requester: ReplicaId,
        public val sender: ReplicaId,
        public val fromSeq: Long,
        public val toSeq: Long,
    ) : QuiltMessage<S>()

    /**
     * A delivered-version-vector gossip from [sender] — [sender]'s whole-room
     * contiguous **delivered** [VersionVector] (`author → highest gap-free seq it has
     * applied), the per-replica row of the matrix clock that decides causal stability
     * for RGA GC (ADR-003 addendum v3, #262).
     *
     * Kept **separate from [Ack]** deliberately: [Ack] is per-author progress on
     * *this* replica's own deltas and rides the delta/ack cadence; [Delivered] is a
     * cross-author whole-room snapshot gossiped on its own cadence (on local apply and
     * on the anti-entropy tick). The recipient stores it as that peer's matrix row.
     */
    @Serializable
    @SerialName("delivered")
    public class Delivered<S>(
        public val sender: ReplicaId,
        public val vector: VersionVector,
    ) : QuiltMessage<S>()
}
