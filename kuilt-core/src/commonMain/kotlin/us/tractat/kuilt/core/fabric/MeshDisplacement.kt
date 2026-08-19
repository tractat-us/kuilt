package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId

/**
 * Something the mesh's duplicate-link dedup did that a consumer's own logger should be able to see.
 *
 * `kuilt-core` is logger-free by contract, so a mesh takes an `onDisplacement` callback the way
 * [CompositeLoom] takes `onPlyFailure`: the seam raises the event, the consumer records it. Both
 * cases below are *conditions*, not faults — the mesh keeps working through either — but each is
 * the only externally visible trace of a decision that moved a peer's frames between links, and
 * [OrderingHoldOverflowed] in particular records a moment at which [us.tractat.kuilt.core.Seam.incoming]'s
 * send-order promise was deliberately traded for liveness (#2474).
 *
 * Every field is an identity or a state, never a size alone: a count says *that* something happened,
 * the identities say *what*.
 */
public sealed interface MeshDisplacement {

    /** The peer whose links were involved. */
    public val peer: PeerId

    /** Which arm of the dedup produced the drained link. */
    public enum class Arm {
        /** The peer had been PUBLISHED on the drained link and was moved off it — the swap window. */
        Replace,

        /** The drained link resolved second and lost; the peer was never published on it locally. */
        Keep,
    }

    /** How a drain ended — the field that says whether the mechanism worked or was backstopped. */
    public enum class Outcome {
        /** The remote's in-band goodbye arrived: everything it wrote on that link is now behind us. */
        Goodbye,

        /** The link died under the drain — closed, errored, or refused the goodbye. */
        LinkLoss,

        /** The drain bound expired with no goodbye and no close: a zombie link, backstopped. */
        Bound,
    }

    /**
     * A displaced link finished draining and was disposed of.
     *
     * [framesDrained] is what the drain actually *saved*: frames the remote delivered on that link
     * after this seam had already moved the peer off it, which the pre-#2474 abrupt close destroyed.
     * A zero is a benign displacement and is reported too — it is what makes a non-zero one legible.
     */
    public data class Drained(
        override val peer: PeerId,
        public val arm: Arm,
        public val outcome: Outcome,
        public val framesDrained: Int,
    ) : MeshDisplacement

    /**
     * The receiver ordering hold for [peer] hit [capacity] and released early, **delivering out of
     * send order** for that peer rather than backpressuring.
     *
     * Backpressure is the one option not available at the bound: the hold is applied while staging a
     * frame for delivery, and suspending there would stall every link's staging on a release that
     * only the drained link's own goodbye can perform. So the reorder is accepted and reported,
     * loudly and boundedly, in preference to a silent wedge.
     */
    public data class OrderingHoldOverflowed(
        override val peer: PeerId,
        public val buffered: Int,
        public val capacity: Int,
    ) : MeshDisplacement
}
