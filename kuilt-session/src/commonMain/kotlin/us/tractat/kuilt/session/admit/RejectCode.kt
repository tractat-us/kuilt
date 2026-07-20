package us.tractat.kuilt.session.admit

/**
 * Why a host refused a joiner's [AdmitMessage.Hello] or [AdmitMessage.Resume].
 *
 * The structured companion to [AdmitMessage.Reject.reason], which stays free text for humans
 * reading a log. Before this existed a consumer had to string-match the reason to tell a
 * transient refusal from a permanent one — and the two most consequential cases were not even
 * distinguishable in principle, because the host sent the same words for both.
 *
 * **Open hierarchy (interface, not enum)**, mirroring
 * [us.tractat.kuilt.core.discovery.DiscoveryKind]: a module outside `:kuilt-session` can supply
 * its own code without amending this file. A code kuilt does not recognise — including one from a
 * newer build, or from a peer that sends none at all — decodes as [Unknown].
 *
 * **[retryable] defaults to `true`, deliberately.** Retrying is what the joiner did before typed
 * codes existed, so an unrecognised code preserves that behaviour exactly; only a code that
 * explicitly declares itself terminal changes it. The dangerous direction is the other one:
 * treating an unknown refusal as terminal would break the fast-reconnect race, where a resume
 * that arrives before the host's detector fires is rejected and a retry a moment later succeeds.
 *
 * @sample us.tractat.kuilt.session.classifyRejectCodeSample
 */
public interface RejectCode {
    /** Stable wire identifier. Travels in [AdmitMessage.Reject]; must not change once shipped. */
    public val id: String

    /**
     * Whether presenting the same credentials again could plausibly succeed.
     *
     * `true` (the default) means the refusal is transient — keep retrying to the deadline.
     * `false` means retrying is futile and the caller should surface the failure now.
     */
    public val retryable: Boolean get() = true

    /** The joiner named a room this host does not serve. Terminal. */
    public object RoomMismatch : RejectCode {
        override val id: String = "room-mismatch"
        override val retryable: Boolean = false
    }

    /**
     * No reconnect window is open for this peer *yet* — the fast-reconnect race: the joiner
     * re-wove and resumed before the host's own liveness detector noticed the drop. Retryable,
     * and the retry is what recovers the session.
     */
    public object ResumeWindowNotYetOpen : RejectCode {
        override val id: String = "resume-window-not-yet-open"
    }

    /**
     * The joiner declared an admit-protocol version this host cannot speak — outside
     * [ProtocolVersion.MIN_SUPPORTED]..[ProtocolVersion.MAX_SUPPORTED] (#1569). Terminal: retrying
     * a version you don't support is futile; the peer must upgrade or downgrade its build. A joiner
     * that sends *no* version (a peer predating the field) is admitted as legacy, so this code
     * never fires for the additive case.
     */
    public object ProtocolMismatch : RejectCode {
        override val id: String = "protocol-mismatch"
        override val retryable: Boolean = false
    }

    /** The reconnect window closed — it elapsed, or the token was already spent. Terminal. */
    public object ResumeWindowExpired : RejectCode {
        override val id: String = "resume-window-expired"
        override val retryable: Boolean = false
    }

    /** The token failed structural validation (wrong room). Terminal. */
    public object ResumeTokenInvalid : RejectCode {
        override val id: String = "resume-token-invalid"
        override val retryable: Boolean = false
    }

    /**
     * A code this build does not know: a peer that predates typed codes (empty [id]), a newer
     * build's addition, or another module's own code. Retryable — see the type KDoc.
     */
    public data class Unknown(override val id: String) : RejectCode

    public companion object {
        /** The code of a [AdmitMessage.Reject] that carries none — an [Unknown] with an empty id. */
        public val Unspecified: Unknown = Unknown("")

        /**
         * Resolve a wire [id] to its code. A null id (the peer sent none) or an unrecognised one
         * yields [Unknown], never an error — an admit frame that fails to decode is dropped
         * silently, which would hang a handshake that has no timeout.
         *
         * A `when` over the ids rather than a lookup table over the objects: a companion-held
         * `listOf(RoomMismatch, …)` is initialised *before* those nested objects are, so it holds
         * nulls at first use.
         */
        public fun fromId(id: String?): RejectCode = when (id) {
            null -> Unspecified
            RoomMismatch.id -> RoomMismatch
            ProtocolMismatch.id -> ProtocolMismatch
            ResumeWindowNotYetOpen.id -> ResumeWindowNotYetOpen
            ResumeWindowExpired.id -> ResumeWindowExpired
            ResumeTokenInvalid.id -> ResumeTokenInvalid
            else -> Unknown(id)
        }
    }
}
