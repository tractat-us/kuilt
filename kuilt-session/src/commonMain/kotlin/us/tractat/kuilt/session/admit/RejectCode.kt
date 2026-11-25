package us.tractat.kuilt.session.admit

/**
 * Why a host refused a joiner's [AdmitMessage.Hello].
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
 * treating an unknown refusal as terminal would end a joiner's reconnect on a refusal its host
 * meant as passing, such as [HostFailed], and the retry a moment later would have succeeded.
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
     * The joiner declared an admit-protocol version this host cannot speak — outside
     * [ProtocolVersion.MIN_SUPPORTED]..[ProtocolVersion.MAX_SUPPORTED] (#1569). Terminal: retrying
     * a version you don't support is futile; the peer must upgrade or downgrade its build. A joiner
     * that sends *no* version (a peer predating the field) is refused with it too.
     */
    public object ProtocolMismatch : RejectCode {
        override val id: String = "protocol-mismatch"
        override val retryable: Boolean = false
    }

    /**
     * The joiner's proof of its device key failed (#3211): it was not signed by the key its `Hello`
     * named, or it answered another challenge, room or host. Terminal: the same device answering the
     * same way fails the same way.
     */
    public object BadProof : RejectCode {
        override val id: String = "bad-proof"
        override val retryable: Boolean = false
    }

    /**
     * The joiner claimed the peer id of a connected member under a different device key (#3159).
     * Terminal: another device is using that id.
     */
    public object KeyConflict : RejectCode {
        override val id: String = "key-conflict"
        override val retryable: Boolean = false
    }

    /**
     * The joiner said Hello under a peer id whose seat another id of the same device took over, and
     * under the key that was superseded (Decided 32, #3695). The `Reject` names the successor
     * ([AdmitMessage.Reject.supersededBy]), and the joiner ends
     * `Left(self, LeaveReason.Superseded(by))` rather than an admission failure. Terminal: another
     * process of this device holds the membership, and a Hello from this one would take it back.
     */
    public object Superseded : RejectCode {
        override val id: String = "superseded"
        override val retryable: Boolean = false
    }

    /**
     * The host could not do its own half of the key-proving exchange (#3211): signing its challenge,
     * or checking the joiner's proof, failed on the host's platform. Retryable: the fault is the
     * host's, and nothing about the joiner's credentials was judged.
     *
     * Distinct from [Unspecified], which means a `Reject` carrying no code at all, so an app can tell
     * a host's passing failure from a peer that predates typed codes.
     */
    public object HostFailed : RejectCode {
        override val id: String = "host-failed"
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
            BadProof.id -> BadProof
            KeyConflict.id -> KeyConflict
            Superseded.id -> Superseded
            HostFailed.id -> HostFailed
            else -> Unknown(id)
        }
    }
}
