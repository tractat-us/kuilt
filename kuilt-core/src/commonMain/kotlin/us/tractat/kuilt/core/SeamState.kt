package us.tractat.kuilt.core

/**
 * One peer's view of whether the fabric can carry frames.
 *
 * Orthogonal to [Seam.peers]: [Woven] with `peers == {selfId}` is a fully
 * legitimate, well-defined state — the fabric is live and this peer is simply
 * alone in the session. "Can I inject frames" (this) and "who can I reach"
 * (peers) are different questions.
 *
 * Lifecycle: `Weaving → Woven → Torn(reason)`. `Woven → Weaving` is permitted
 * if a fabric supports re-establishment.
 *
 * [Torn] is **unconditionally terminal**. Its only producers are the close *decision*
 * (a local `close()`, via `SeamStateGate.tear`) and self-driven transport death (a fabric
 * latching `Torn` when its remote is permanently gone). A seam that is degraded but
 * *recoverable* — e.g. a multipath rollup whose plies/tiers are all currently down —
 * publishes [Weaving], never a derived, revivable `Torn`. So `state is Torn` may be read
 * directly as "this fabric is gone for good."
 *
 * **Caveat — terminal per fabric generation.** A `Seam` *handle* wrapping a resumable
 * transport can be observed to leave `Torn` when a same-instance resume swaps in a fresh
 * delegate (e.g. `MuxClientLoom`'s join-resume path re-weaves the underlying link behind a
 * stable handle). That is a new fabric generation under the same handle, not the original
 * flow un-tearing: the torn underlying seam stays torn. Consumers keying lifecycle on a
 * single seam generation may read `Torn` as terminal for that generation.
 */
public sealed interface SeamState {
    /**
     * The fabric is forming — or is degraded but recoverable (e.g. a multipath rollup with no
     * live ply right now). Sends may reach no one and must not be relied on, but are best-effort:
     * a send while [Weaving] never throws (delivery is simply not guaranteed until [Woven]).
     */
    public data object Weaving : SeamState

    /** The fabric is live. Frames broadcast now are carried to the current peers. */
    public data object Woven : SeamState

    /**
     * Unconditionally terminal — the fabric is gone for good; [reason] says why. Produced only by
     * the close decision or self-driven transport death, never by a recoverable rollup.
     */
    public data class Torn(val reason: CloseReason) : SeamState
}
