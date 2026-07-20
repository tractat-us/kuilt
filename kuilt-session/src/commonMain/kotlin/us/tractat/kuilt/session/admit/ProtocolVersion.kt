package us.tractat.kuilt.session.admit

/**
 * The admit-handshake protocol version this build speaks, and the range of versions it can admit.
 *
 * Two peers running incompatible kuilt builds otherwise complete the handshake and then fail
 * later, opaquely, on the first frame neither side can decode. A joiner declares its version in
 * [AdmitMessage.Hello.protocolVersion]; the host compares it against [MIN_SUPPORTED]..[MAX_SUPPORTED]
 * and rejects a mismatch at admit time with [RejectCode.ProtocolMismatch] (#1569).
 *
 * **The version is the single source of truth** — never a magic number scattered across call
 * sites. A joiner stamps [CURRENT]; the host gates on [isSupported].
 *
 * **Additive on the wire:** a peer that predates the field sends no version, which decodes as
 * `null` and is treated as legacy — [isSupported] admits it. Version negotiation must never lock
 * out an older peer; only a peer that *declares* an out-of-range version is refused.
 */
public object ProtocolVersion {
    /** The protocol version this build stamps into every [AdmitMessage.Hello] it sends. */
    public const val CURRENT: Int = 1

    /** Oldest declared version this build will admit. */
    public const val MIN_SUPPORTED: Int = 1

    /** Newest declared version this build will admit. */
    public const val MAX_SUPPORTED: Int = 1

    /**
     * Whether a joiner's declared [version] is one this host can admit.
     *
     * `null` (a peer that predates the version field — the legacy, additive case) is admitted; any
     * declared version outside [MIN_SUPPORTED]..[MAX_SUPPORTED] is refused.
     */
    public fun isSupported(version: Int?): Boolean =
        version == null || version in MIN_SUPPORTED..MAX_SUPPORTED
}
