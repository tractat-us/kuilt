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
 * ## Version 2 — the star relay (#1994), and why `null` is no longer tolerated
 *
 * Version 2 relays frames between spokes of a star fabric. Version 1 does not, so a v1 peer
 * admitted to a v2 room would have every relayed frame black-holed — the failure the gate exists
 * to prevent, arriving later and more opaquely than a refusal.
 *
 * A peer that predates the version field sends no version, decoding as `null`. Before v2 that was
 * treated as legacy and admitted. It no longer is: `null` means "predates #1569", i.e.
 * *definitionally* incapable of relaying — exactly the population this bump exists to exclude. A
 * version-less peer is locked out of rooms, and that is the intended cost.
 *
 * ## The limit of this gate, stated plainly
 *
 * The gate lives **host-side**, in `SeamRoom.handleAdmitFrame`. A **pre-#1569 host** has no gate at
 * all: it will admit a v2 joiner and then black-hole every relayed frame. Nothing on this side can
 * defend that case — it is documented, not fixed.
 */
public object ProtocolVersion {
    /** The protocol version this build stamps into every [AdmitMessage.Hello] it sends. */
    public const val CURRENT: Int = 2

    /** Oldest declared version this build will admit. */
    public const val MIN_SUPPORTED: Int = 2

    /** Newest declared version this build will admit. */
    public const val MAX_SUPPORTED: Int = 2

    /**
     * Whether a joiner's declared [version] is one this host can admit.
     *
     * `null` — a peer predating the version field — is **refused**. See the class KDoc for why the
     * pre-v2 permissive carve-out was closed rather than kept.
     */
    public fun isSupported(version: Int?): Boolean =
        version != null && version in MIN_SUPPORTED..MAX_SUPPORTED
}
