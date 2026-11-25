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
 * ## Version 4 — the key must be proved (#3211)
 *
 * Version 4 sends the Challenge and Proof frames that version 3 only defined: a host admits a joiner
 * only once it has answered the host's signed challenge with a proof signed by the key its Hello
 * named. A v3 joiner sends no proof, so a v3 joiner admitted by a v4 host would wait out its deadline
 * instead of hearing why; the gate refuses it with [RejectCode.ProtocolMismatch] instead.
 *
 * ## Version 5 — a proven key restores its held seat (#3212)
 *
 * Version 5 decides every verified proof against the seats the host holds: the same peer id under
 * the same key gets its seat back, marked on the direct Welcome as [ReturnKind.Restored], and the
 * same peer id under a different key is refused [RejectCode.KeyConflict] while that seat's member
 * is connected. A v4 joiner reads neither, so the gate refuses it.
 *
 * ## Version 6 — a known key under a new peer id supersedes its old seat (#3213)
 *
 * Version 6 lets a proven key under a new peer id take over that key's **partitioned** seat: the old
 * member leaves with [us.tractat.kuilt.session.LeaveReason.Superseded], and every other member is
 * sent a [AdmitMessage.Farewell] whose `supersededBy` names the new id, ahead of its roster Welcome.
 * The superseded member is sent the same Farewell and ends its room rather than saying Hello again.
 * A v5 member would read that Farewell as a clean leave and, if superseded, reconnect, so the gate
 * refuses it.
 *
 * ## Version 7 — a return is a Hello, and every answer names its Hello (#3215)
 *
 * Version 7 retires the `Resume` and `ResumeAck` frames: a joiner coming back after a dropped link
 * says Hello again, and its device key proves the seat is its own. The direct Welcome and every
 * Reject carry the nonce of the Hello they answer, so a joiner acts only on an answer to its latest
 * Hello, and the direct Welcome carries the id of the membership it admits, so a returning joiner
 * can tell its own seat from a fresh admission. The rest of the admission burst, a Welcome per
 * existing member and the host's intro, carries the same nonce (#3615), so a joiner drops a late
 * frame of a burst answering an older Hello and rebuilds its roster only on the latest burst. A v6
 * joiner reads neither, so it would still take an answer to an older Hello as the answer to its
 * latest one; the gate refuses it.
 *
 * The burst's nonce arrived after the rest of v7, under the same number: #3641 added
 * [AdmitMessage.Welcome.answers] to the bootstrap Welcomes and the host intro without a bump. No
 * release tag carries the earlier form (v0.7.6 speaks v2), so no peer in the field speaks it.
 *
 * **A Hello from a superseded id, under the key that was superseded, is refused, not challenged.**
 * While the id that took the seat over is seated, the host answers it with a [AdmitMessage.Reject]
 * that names it, under [RejectCode.Superseded], whose `supersededBy` names the successor (Decided
 * 32). Under any other key the id is challenged and decided like any Hello. The host remembers at
 * most 64 superseded ids, oldest forgotten first, and a forgotten id is challenged too. Because the
 * refusal names the Hello, a joiner acts on it with or without an identified host: a superseded
 * process whose link has healed, and a new process of the same device reusing the id, both end with
 * `Left(self, Superseded(by))`. This arrived after the rest of v7, under the same number, like the
 * burst's nonce above.
 *
 * **One answer may name no Hello.** A host older than v7 cannot name one, and what it sends a v7
 * Hello is [RejectCode.ProtocolMismatch]. So before its first admission a joiner acts on a
 * `ProtocolMismatch` that names no Hello, and fails the join with it; ignoring it would turn the old
 * host's answer into an admit timeout. Every other code, and the same code once the joiner has been
 * admitted (a reconnect included), counts only when it names the latest Hello.
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
    public const val CURRENT: Int = 7

    /** Oldest declared version this build will admit. */
    public const val MIN_SUPPORTED: Int = 7

    /** Newest declared version this build will admit. */
    public const val MAX_SUPPORTED: Int = 7

    /**
     * Whether a joiner's declared [version] is one this host can admit.
     *
     * `null` — a peer predating the version field — is **refused**. See the class KDoc for why the
     * pre-v2 permissive carve-out was closed rather than kept.
     */
    public fun isSupported(version: Int?): Boolean =
        version != null && version in MIN_SUPPORTED..MAX_SUPPORTED
}
