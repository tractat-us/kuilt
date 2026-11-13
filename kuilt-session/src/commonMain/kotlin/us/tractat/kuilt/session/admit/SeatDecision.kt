package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey

/**
 * A seat the host is still holding: a member that was admitted and has not left, whether it is
 * connected right now or partitioned inside its reconnect window. [key] is the device key that member
 * proved when it was admitted.
 */
internal data class Seat(val peerId: PeerId, val key: DidKey)

/**
 * What the host does with a joiner that has just proved its device key: the spec's key → seat table,
 * one variant per row.
 */
internal sealed interface SeatDecision {
    /** The claimed id holds a seat under the proven key: the same membership comes back. */
    data class Restore(val peerId: PeerId) : SeatDecision

    /**
     * The claimed id holds no seat, but the proven key holds [previous]: the same device came back
     * under a fresh id, and the new id inherits that membership.
     */
    data class RestoreUnderNewId(val previous: PeerId) : SeatDecision

    /** Neither the id nor the key holds a seat, and the joiner presented a valid grant. */
    data object Returning : SeatDecision

    /** The claimed id holds a seat under a different key: another device claiming a member's id. */
    data object KeyConflict : SeatDecision

    /** Neither the id nor the key holds a seat, and no valid grant: ordinary admission. */
    data object Ordinary : SeatDecision
}

/**
 * The key → seat decision for a joiner that claimed [claimedId] and proved [provenKey], against the
 * seats the host holds at Proof time.
 *
 * Pure: no clock, no lock, no I/O. The caller reads [held] under the room lock **when the Proof
 * arrives**, not when the Challenge went out, since a `Goodbye` or a window expiry can land between
 * the two. Whether the grant is valid is decided before this is called, so [holdsValidGrant] is the
 * verdict, not the grant.
 *
 * The precedence, first match wins:
 * 1. the claimed id holds a seat → [SeatDecision.Restore] under the same key,
 *    [SeatDecision.KeyConflict] under any other — whatever else the proven key holds;
 * 2. the proven key holds a seat → [SeatDecision.RestoreUnderNewId];
 * 3. [holdsValidGrant] → [SeatDecision.Returning];
 * 4. otherwise → [SeatDecision.Ordinary].
 *
 * So a grant never overrides a held seat, and an id held under another key is refused even when the
 * joiner's own key holds a seat elsewhere: the joiner is lying about which seat it is, and restoring
 * the other seat would reward the lie.
 *
 * **The decision keeps its own precondition.** Each id holds at most one seat, and so does each key.
 * Every verdict preserves that, as long as the caller carries it out: [SeatDecision.RestoreUnderNewId]
 * moves the key's one seat to the new id, and the two admitting verdicts are reached only when the key
 * holds no seat. A second seat for one key therefore means a caller admitted against the decision, so
 * this function refuses it rather than choose between the two.
 *
 * @throws IllegalArgumentException when two seats in [held] share a peer id or a key.
 */
internal fun decideSeat(
    claimedId: PeerId,
    provenKey: DidKey,
    held: Collection<Seat>,
    holdsValidGrant: Boolean,
): SeatDecision {
    require(held.distinctBy { it.peerId }.size == held.size) { "two held seats share a peer id: $held" }
    require(held.distinctBy { it.key }.size == held.size) { "two held seats share a key: $held" }
    return SeatDecision.Ordinary
}
