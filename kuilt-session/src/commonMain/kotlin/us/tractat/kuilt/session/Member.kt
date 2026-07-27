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
     * On a member watching *another* member, this deadline **starts as a local estimate whenever
     * that member's own detector fires first, and is then replaced by the host's authoritative**
     * [AdmitMessage.Paused][us.tractat.kuilt.session.admit.AdmitMessage.Paused] — in either arrival
     * order, and without re-announcing the partition. The host is the only holder of the enforced
     * window, so its number always wins. On a joiner watching its *host*, the joiner's own reconnect
     * budget is the authority and no refinement occurs.
     *
     * **A refinement is announced.** Moving this field emits a fresh
     * [MembershipEvent.WindowOpened][us.tractat.kuilt.session.MembershipEvent.WindowOpened] carrying
     * the new deadline, so the event stream and the roster cannot disagree — a silent move would
     * leave the last announcement a consumer heard permanently false. The corollary for a consumer
     * that *does* key on the event: a later `WindowOpened` for the same peer **supersedes** the
     * earlier one; hold the latest, do not assume the first is final. Keying on this level avoids
     * the question entirely, which is why it exists.
     *
     * Beware that the two fields can then come from **different clocks**. `markPartitioned` derives
     * both from the local clock, but the `Paused` path pairs a local [since] with the *host's*
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
