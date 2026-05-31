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
 * if a fabric supports re-establishment; [Torn] is terminal.
 */
public sealed interface SeamState {
    /** The fabric is forming. Sends may reach no one and must not be relied on. */
    public data object Weaving : SeamState

    /** The fabric is live. Frames broadcast now are carried to the current peers. */
    public data object Woven : SeamState

    /** Terminal. The fabric is gone; [reason] says why. */
    public data class Torn(val reason: CloseReason) : SeamState
}
