package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId

/**
 * An admitted member of a [Room].
 *
 * A peer is a [Member] only after completing the admit/identify handshake.
 * Raw [Seam][us.tractat.kuilt.core.Seam] peers that have connected but not yet
 * identified are not members.
 *
 * [identity] is *self-asserted* by the peer in its `Hello`; [principal] is the
 * host's *verified* identity for the connection (when the fabric authenticates it —
 * see [PrincipalAttested]). It is `null` for unauthenticated connections and on the
 * joiner side (a joiner does not verify the host).
 */
public data class Member(
    val id: PeerId,
    val identity: MemberIdentity,
    val liveness: Liveness,
    val principal: Principal? = null,
)

/**
 * Stable identity for a room member, used for dedup across reconnects.
 *
 * [deviceId] is a hardware-stable identifier (preferred for dedup — survives app restart).
 * [sessionId] is a session-scoped identifier minted at join time (fallback when
 * [deviceId] is absent, e.g. on platforms that don't expose a stable hardware id).
 */
public data class MemberIdentity(
    val displayName: String,
    val sessionId: String,
    val deviceId: String? = null,
) {
    /**
     * The stable key used for reconnect dedup. Prefers [deviceId] when present,
     * falls back to [sessionId].
     */
    val dedupKey: String get() = deviceId ?: sessionId
}

/** The liveness state of an admitted member. */
public enum class Liveness {
    /** The member's transport link is active. */
    Connected,

    /** The member's transport link has dropped; reconnect window may be open. */
    Partitioned,
}
