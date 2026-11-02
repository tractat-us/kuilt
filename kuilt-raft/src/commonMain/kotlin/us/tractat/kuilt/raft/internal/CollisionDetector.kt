package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey

/**
 * Passive detector for two live writers sharing one [ClientId]. The mark starts at [issuedThrough],
 * the floor a durable id's caller handed over (`0` for an auto id), and the owning engine raises it
 * with every serial it issues. A committed entry under the engine's own id bearing a serial above the
 * mark reads as a foreign writer.
 *
 * Nothing the log holds raises it: a retained foreign serial would otherwise pass for this id's own
 * on the next boot, so a restart would erase each collision as it happened.
 *
 * It sees only what is above the mark. A foreign serial at or below it passes, within one incarnation
 * as well as across a restart (tracked by #3081). A floor behind what the id really issued makes its
 * own history read as foreign: see [us.tractat.kuilt.raft.ClientIdentity.Durable]. Confined to the
 * engine's single dispatch loop; no internal locking.
 */
internal class CollisionDetector(private val myClientId: ClientId, issuedThrough: Long) {
    private var maxIssued = issuedThrough

    /** Raise the mark to [requestId] if it is higher: an auto-counter bump or an explicit requestId. */
    fun issued(requestId: Long) { if (requestId > maxIssued) maxIssued = requestId }

    /** True iff [key] is under this engine's identity with a serial above the mark. */
    fun isForeign(key: DedupKey?): Boolean =
        key != null && key.clientId == myClientId && key.requestId > maxIssued
}
