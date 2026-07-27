package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId
import kotlin.time.Instant

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

/**
 * The liveness state of an admitted member.
 *
 * A **level**, and the authoritative one: [Room.roster] is a StateFlow, so a late subscriber reads
 * the current value and can never miss a partition the way an events collector can (#1618 Q2).
 * Prefer this over replaying [MembershipEvent]s — in particular, do not key a UI on
 * [MembershipEvent.Recovered] vs [MembershipEvent.Resumed], which differ by role and by recovery
 * path; the level clears on either.
 */
public sealed interface Liveness {
    /** The member's transport link is active. */
    public data object Connected : Liveness

    /**
     * The member's transport link has dropped and its seat is held open until [windowExpiresAt].
     *
     * [windowExpiresAt] is **non-null by construction** — it is written at the same site and
     * instant that sets this state, so a partitioned member whose window is unknown is not a state
     * this type can represent. That is deliberate: it was previously reachable only by replaying a
     * [MembershipEvent.WindowOpened] that some paths never emitted (#1723, #1724).
     *
     * On a member watching *another* member, this deadline is **intended** to start as a local
     * estimate and then be refined by the host's authoritative
     * [AdmitMessage.Paused][us.tractat.kuilt.session.admit.AdmitMessage.Paused] — that refinement is
     * not implemented yet (#1724 lands it; today `handlePaused` returns early on an already-
     * partitioned member, so a local estimate stands). On a joiner watching its *host*, the joiner's
     * own reconnect budget is the authority and no refinement occurs.
     *
     * Beware that the two fields can come from **different clocks**. `markPartitioned` derives both
     * from the local clock, but the `Paused` path pairs a local [since] with the *host's*
     * [windowExpiresAt]. So treat [windowExpiresAt] as a deadline to compare the local clock
     * against — never as an interval to subtract from [since], which host↔member skew would distort.
     */
    public data class Partitioned(
        /**
         * When this partition was **first** detected — preserved across an idempotent re-detection
         * rather than advanced, so it agrees with the single [MembershipEvent.Partitioned] that was
         * actually emitted (that event fires only on the first detection). Do not "simplify" this to
         * overwrite on every detection: `since` would then drift forward while [windowExpiresAt]
         * stayed pinned, and could eventually exceed it.
         */
        val since: Instant,
        val windowExpiresAt: Instant,
    ) : Liveness
}
