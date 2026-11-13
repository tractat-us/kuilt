package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey

/**
 * A seat the host is still holding for a joiner: a member that was admitted and has not left. [key]
 * is the device key that member proved when it was admitted. The peer id is not a field: seats are
 * held in a `Map<PeerId, Seat>`, which is what makes "one seat per id" a property of the type.
 */
internal data class Seat(val key: DidKey, val liveness: SeatLiveness)

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
    /** The verdicts that admit the joiner as a new membership rather than restoring a held one. */
    sealed interface NewMember : SeatDecision

    /**
     * The claimed id holds a seat under the proven key, connected or partitioned: the same membership
     * comes back.
     */
    data class Restore(val peerId: PeerId) : SeatDecision

    /**
     * The claimed id holds no seat, but the proven key holds [previous], connected or partitioned: the
     * same device came back under a fresh id, and the new id inherits that membership.
     */
    data class RestoreUnderNewId(val previous: PeerId) : SeatDecision

    /**
     * The claimed id holds a **partitioned** seat under a different key, and the proven key holds no
     * seat: the old seat is released and the joiner is admitted as [admission]. This is how a device
     * whose key store was cleared, and whose peer id is durable, gets back in with no prompt.
     *
     * [admission] is what [decideSeat] answers once the released seat is gone, so a valid grant makes
     * the new member [Returning].
     */
    data class ReleaseAndAdmit(val released: PeerId, val admission: NewMember) : SeatDecision

    /** Neither the id nor the key holds a seat, and the joiner presented a valid grant. */
    data object Returning : NewMember

    /**
     * Terminal refusal. Either the claimed id holds a **connected** seat under a different key — a
     * different device claiming a present member's id — or the claimed id holds a seat under a
     * different key while the proven key holds another seat, whatever either seat's liveness.
     */
    data object KeyConflict : SeatDecision

    /** Neither the id nor the key holds a seat, and no valid grant: ordinary admission. */
    data object Ordinary : NewMember
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
 * [held] holds **joiner seats only, never the host's own.** What happens when a joiner presents the
 * host's key, or claims the host's id, is not this function's question: the caller settles it before
 * calling. Put the host's seat in [held] and a joiner presenting the host's key under a fresh id
 * would be told to take over the host's membership.
 *
 * The precedence, first match wins:
 * 1. the claimed id holds a seat under the proven key → [SeatDecision.Restore];
 * 2. the claimed id holds a seat under another key, and the proven key holds a seat too →
 *    [SeatDecision.KeyConflict]: releasing the id's seat would leave one key holding two, and
 *    restoring the key's seat would reward a joiner that lied about which seat it is;
 * 3. the claimed id holds a connected seat under another key → [SeatDecision.KeyConflict];
 * 4. the claimed id holds a partitioned seat under another key → [SeatDecision.ReleaseAndAdmit];
 * 5. the proven key holds a seat → [SeatDecision.RestoreUnderNewId];
 * 6. [holdsValidGrant] → [SeatDecision.Returning];
 * 7. otherwise → [SeatDecision.Ordinary].
 *
 * So a grant never overrides a held seat.
 *
 * **Falling through means deciding again.** When a restore cannot be carried out — the reconnect
 * window refuses it, or the device was revoked since it was admitted — the caller calls this again
 * with the seat removed from [held], and acts on that answer. It does not substitute
 * [SeatDecision.Ordinary]: the answer may be [SeatDecision.Returning], or another seat under the same
 * key. [SeatDecision.ReleaseAndAdmit] is that same re-decision, made here. Revocation is the
 * caller's to check; this function never sees it.
 *
 * **One seat per key is the admission site's to keep, not this function's.** Two seats under one key
 * is reachable: two tabs of one device can both be decided [SeatDecision.Ordinary] before either is
 * added. The admission site keeps it from happening by deciding again under the room lock just before
 * it adds the seat. If two do exist, this function still answers: [SeatDecision.Restore] when the
 * claimed id is one of them, and otherwise [SeatDecision.RestoreUnderNewId] naming the one with the
 * lowest peer id, so the answer does not depend on the map's iteration order. The other seat is left
 * as it is.
 */
internal fun decideSeat(
    claimedId: PeerId,
    provenKey: DidKey,
    held: Map<PeerId, Seat>,
    holdsValidGrant: Boolean,
): SeatDecision {
    val byId = held[claimedId]
    val byKey = held.entries.filter { it.value.key == provenKey }.minByOrNull { it.key.value }?.key
    val admission = if (holdsValidGrant) SeatDecision.Returning else SeatDecision.Ordinary
    return when {
        byId != null && byId.key == provenKey -> SeatDecision.Restore(claimedId)
        byId != null && byKey != null -> SeatDecision.KeyConflict
        byId != null && byId.liveness == SeatLiveness.Connected -> SeatDecision.KeyConflict
        byId != null -> SeatDecision.ReleaseAndAdmit(released = claimedId, admission = admission)
        byKey != null -> SeatDecision.RestoreUnderNewId(previous = byKey)
        else -> admission
    }
}
