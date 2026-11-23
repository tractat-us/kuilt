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
 * ## Version 3 — the device key (#3210)
 *
 * Version 3 adds the joiner's device key and a fresh joiner nonce to the Hello, a key to every
 * Welcome, and the Challenge and Proof frames that prove the key. A v2 peer sends no key, so it
 * cannot take part in that proof, and the spec rules out mixed versions: v2 is refused like every
 * older peer. A Hello that declares v3 but omits the key or the nonce is refused with the same
 * [RejectCode.ProtocolMismatch], by the same host-side gate.
 *
 * Every later change to what a joiner must send to be admitted bumps this line again.
 *
 * ## The limit of this gate, stated plainly
 *
 * The gate lives **host-side**, in `SeamRoom.handleAdmitFrame`. A **pre-#1569 host** has no gate at
 * all: it will admit a v2 joiner and then black-hole every relayed frame. Nothing on this side can
 * defend that case — it is documented, not fixed.
 */
public object ProtocolVersion {
    /** The protocol version this build stamps into every [AdmitMessage.Hello] it sends. */
    public const val CURRENT: Int = 3

    /** Oldest declared version this build will admit. */
    public const val MIN_SUPPORTED: Int = 3

    /** Newest declared version this build will admit. */
    public const val MAX_SUPPORTED: Int = 3

    /**
     * Whether a joiner's declared [version] is one this host can admit.
     *
     * `null` — a peer predating the version field — is **refused**. See the class KDoc for why the
     * pre-v2 permissive carve-out was closed rather than kept.
     */
    public fun isSupported(version: Int?): Boolean =
        version != null && version in MIN_SUPPORTED..MAX_SUPPORTED
}
