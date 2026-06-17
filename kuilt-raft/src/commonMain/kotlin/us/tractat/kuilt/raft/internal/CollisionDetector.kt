package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey

/**
 * Passive, exact detector for two live writers sharing one [ClientId]. The owning engine records
 * every serial it issues; a committed entry under the engine's own id bearing a serial above that
 * high-water-mark proves a foreign writer — a node can never legitimately observe its own id paired
 * with a serial it never issued. Confined to the engine's single dispatch loop; no internal locking.
 */
internal class CollisionDetector(private val myClientId: ClientId) {
    private var maxIssued = 0L

    /** Record a serial this engine issued (auto-counter bump or explicit requestId). */
    fun issued(requestId: Long) { if (requestId > maxIssued) maxIssued = requestId }

    /** True iff [key] is under this engine's identity with a serial it never issued. */
    fun isForeign(key: DedupKey?): Boolean =
        key != null && key.clientId == myClientId && key.requestId > maxIssued
}
