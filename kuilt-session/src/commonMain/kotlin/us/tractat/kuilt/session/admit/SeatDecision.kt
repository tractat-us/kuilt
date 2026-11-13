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
 * What the host does with a joiner that has just proved its device key: the spec's key → seat table,
 * one variant per outcome.
 */
internal sealed interface SeatDecision {
    /** The verdicts that admit the joiner as a new membership rather than restoring a held one. */
    sealed interface NewMember : SeatDecision

    /** The claimed id holds a seat under the proven key: the same membership comes back. */
    data class Restore(val peerId: PeerId) : SeatDecision

    /**
     * The claimed id holds no seat, but the proven key holds [previous]: the same device came back
     * under a fresh id, and the new id inherits that membership.
     */
    data class RestoreUnderNewId(val previous: PeerId) : SeatDecision

    /**
     * The claimed id's seat is released, then the joiner is admitted as [admission].
     */
    data class ReleaseAndAdmit(val released: PeerId, val admission: NewMember) : SeatDecision

    /** Neither the id nor the key holds a seat, and the joiner presented a valid grant. */
    data object Returning : NewMember

    /** The claimed id holds a seat under a different key: another device claiming a member's id. */
    data object KeyConflict : SeatDecision

    /** Neither the id nor the key holds a seat, and no valid grant: ordinary admission. */
    data object Ordinary : NewMember
}

/**
 * The key → seat decision for a joiner that claimed [claimedId] and proved [provenKey], against the
 * seats the host holds at Proof time.
 *
 * @throws IllegalArgumentException when two seats in [held] share a key.
 */
internal fun decideSeat(
    claimedId: PeerId,
    provenKey: DidKey,
    held: Map<PeerId, Seat>,
    holdsValidGrant: Boolean,
): SeatDecision {
    require(held.values.distinctBy { it.key }.size == held.size) { "two held seats share a key: $held" }
    val byId = held[claimedId]
    val byKey = held.entries.firstOrNull { it.value.key == provenKey }
    return when {
        byId != null && byId.key == provenKey -> SeatDecision.Restore(claimedId)
        byId != null -> SeatDecision.KeyConflict
        byKey != null -> SeatDecision.RestoreUnderNewId(previous = byKey.key)
        holdsValidGrant -> SeatDecision.Returning
        else -> SeatDecision.Ordinary
    }
}
