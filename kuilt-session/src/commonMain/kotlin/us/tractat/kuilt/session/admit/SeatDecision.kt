package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey
import kotlin.time.Instant

/**
 * A seat the host is still holding for a joiner: a member that was admitted and has not left. [key]
 * is the device key that member proved when it was admitted. The peer id is not a field: seats are
 * held in a `Map<PeerId, Seat>`, which is what makes "one seat per id" a property of the type.
 *
 * [lastContact] is when the host last heard anything from the member. It breaks the tie when one key
 * holds several partitioned seats: the one heard from most recently is the one a new id restores
 * (Decided 30). It is not when the host noticed the silence — a closed transport is noticed at once
 * and a quiet one only after the heartbeat timeout, so that order can disagree with this one.
 */
internal data class Seat(val key: DidKey, val liveness: SeatLiveness, val lastContact: Instant)

/** Whether a held seat's member is reachable right now. */
internal enum class SeatLiveness {
    /** The member's link is up. */
    Connected,

    /** The member's link is down and the host is holding its seat inside the reconnect window. */
    Partitioned,
}

/**
 * What the host does with a joiner that has just proved its device key: the key → seat table, one
 * variant per outcome.
 */
internal sealed interface SeatDecision {
    /** The verdicts for a claimed id that holds no seat: what [decideSeat] answers from the key alone. */
    sealed interface FreshId : SeatDecision

    /**
     * The claimed id holds a seat under the proven key, connected or partitioned: the same membership
     * comes back.
     */
    data class Restore(val peerId: PeerId) : SeatDecision

    /**
     * The claimed id holds no seat, but the proven key holds the **partitioned** seat [previous]: the
     * same device came back under a fresh id, and the new id inherits that membership. With several,
     * [previous] is the one heard from most recently (Decided 30).
     */
    data class RestoreUnderNewId(val previous: PeerId) : FreshId

    /**
     * The claimed id holds a **partitioned** seat under a different key: the old seat is released, and
     * the caller then decides the joiner's key again without it ([decideSeat] on the seats that
     * remain), which answers one of the [FreshId] verdicts. This is how a device whose key store was
     * cleared, and whose peer id is durable, gets back in with no prompt (Decided 18); the key it now
     * holds may have seats of its own (Decided 30).
     */
    data class ReleaseAndAdmit(val released: PeerId) : SeatDecision

    /**
     * The claimed id holds no seat, and every seat the proven key holds is connected: another tab of
     * the same device. It is admitted to a seat of its own, and no seat is superseded (Decided 30).
     */
    data object AdditionalSeat : FreshId

    /** Neither the id nor the key holds a seat, and the joiner presented a valid grant. */
    data object Returning : FreshId

    /**
     * Terminal refusal: the claimed id holds a **connected** seat under a different key — a different
     * device claiming a present member's id (Decided 18).
     */
    data object KeyConflict : SeatDecision

    /** Neither the id nor the key holds a seat, and no valid grant: ordinary admission. */
    data object Ordinary : FreshId
}

/**
 * The key → seat decision for a joiner that claimed [claimedId] and proved [provenKey], against the
 * seats the host holds at Proof time.
 *
 * Pure: no clock, no lock, no I/O, and it never throws. The caller reads [held] under the room lock
 * **when the Proof arrives**, not when the Challenge went out, since a `Goodbye` or a window expiry
 * can land between the two. Whether the grant is valid is decided before this is called, so
 * [holdsValidGrant] is the verdict, not the grant.
 *
 * [held] holds **joiner seats only, never the host's own.** A joiner presenting the host's key is
 * decided against the joiner seats that key holds like any other key, so the host's second tab gets
 * a seat of its own, and gets that seat back on a later Hello (Decided 30). Where leaving the host's
 * seat out matters today is rule 1: with it in [held], a joiner claiming the host's own **peer id**
 * under the host's key would be told [SeatDecision.Restore] of the host's membership. A joiner on
 * the host's key under a fresh id would not notice: the host's seat is never partitioned, so it would
 * get [SeatDecision.AdditionalSeat] where it now gets [SeatDecision.Ordinary], and the two seat a
 * joiner identically in slice 1. That difference may become observable in 2.4, once an additional
 * seat is vetted (plan Amendment C).
 *
 * The precedence, first match wins:
 * 1. the claimed id holds a seat under the proven key → [SeatDecision.Restore];
 * 2. the claimed id holds a connected seat under another key → [SeatDecision.KeyConflict];
 * 3. the claimed id holds a partitioned seat under another key → [SeatDecision.ReleaseAndAdmit],
 *    and the caller decides again without that seat, which reaches rules 4–7;
 * 4. the proven key holds a partitioned seat → [SeatDecision.RestoreUnderNewId], naming the one
 *    with the latest [Seat.lastContact];
 * 5. the proven key holds only connected seats → [SeatDecision.AdditionalSeat];
 * 6. [holdsValidGrant] → [SeatDecision.Returning];
 * 7. otherwise → [SeatDecision.Ordinary].
 *
 * So a grant never overrides a held seat, and a connected seat is never superseded by another id.
 *
 * **Falling through means deciding again.** When a restore cannot be carried out — the reconnect
 * window refuses it, or the device was revoked since it was admitted — the caller calls this again
 * with the seat removed from [held], and acts on that answer. It does not substitute
 * [SeatDecision.Ordinary]: the answer may be [SeatDecision.Returning], or another seat under the same
 * key. [SeatDecision.ReleaseAndAdmit] asks for that same re-decision once the seat is released.
 * Revocation is the caller's to check; this function never sees it.
 *
 * **One key may hold many seats (Decided 30), so nothing here assumes one.** Ties on
 * [Seat.lastContact] go to the lowest peer id, so the answer never depends on the map's iteration
 * order.
 */
internal fun decideSeat(
    claimedId: PeerId,
    provenKey: DidKey,
    held: Map<PeerId, Seat>,
    holdsValidGrant: Boolean,
): SeatDecision {
    val byId = held[claimedId]
    return when {
        byId == null -> decideFreshId(provenKey, held, holdsValidGrant)
        byId.key == provenKey -> SeatDecision.Restore(claimedId)
        byId.liveness == SeatLiveness.Connected -> SeatDecision.KeyConflict
        else -> SeatDecision.ReleaseAndAdmit(released = claimedId)
    }
}

/** Rules 4–7 of [decideSeat]: the claimed id holds no seat in [held], so the key alone decides. */
private fun decideFreshId(provenKey: DidKey, held: Map<PeerId, Seat>, holdsValidGrant: Boolean): SeatDecision.FreshId {
    val keySeats = held.entries.filter { it.value.key == provenKey }
    val mostRecent = keySeats
        .filter { it.value.liveness == SeatLiveness.Partitioned }
        .sortedWith(compareByDescending<Map.Entry<PeerId, Seat>> { it.value.lastContact }.thenBy { it.key.value })
        .firstOrNull()
    return when {
        mostRecent != null -> SeatDecision.RestoreUnderNewId(previous = mostRecent.key)
        keySeats.isNotEmpty() -> SeatDecision.AdditionalSeat
        holdsValidGrant -> SeatDecision.Returning
        else -> SeatDecision.Ordinary
    }
}
