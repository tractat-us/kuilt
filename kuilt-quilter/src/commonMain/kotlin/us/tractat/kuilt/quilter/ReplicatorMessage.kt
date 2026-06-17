package us.tractat.kuilt.quilter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector

/**
 * Wire messages exchanged between [SeamReplicator] instances.
 *
 * [Delta] carries a lattice fragment tagged with the sender's monotonic sequence
 * number. [Ack] tells the sender that the acker has absorbed all deltas through
 * [seq], enabling GC of the delta buffer. [FullState] ships the entire current
 * state and is sent once on first contact with a new peer.
 *
 * @param S the [us.tractat.kuilt.crdt.Quilted] state type.
 */
@Serializable
public sealed class ReplicatorMessage<S> {

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
    ) : ReplicatorMessage<S>()

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
    ) : ReplicatorMessage<S>()

    /**
     * The complete current state — sent once on first contact with a peer that
     * has never been seen before. The recipient absorbs it with [us.tractat.kuilt.crdt.Quilted.piece].
     */
    @Serializable
    @SerialName("fullState")
    public class FullState<S>(
        public val sender: ReplicaId,
        public val state: S,
    ) : ReplicatorMessage<S>()

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
    ) : ReplicatorMessage<S>()

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
    ) : ReplicatorMessage<S>()
}
