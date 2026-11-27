package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantTrust
import us.tractat.kuilt.identity.RevocationList

/**
 * **Host only.** For each device key whose membership left, the peer id it held and the last pass the
 * host signed it, kept while that pass is good: not run out, and not voided by the host's
 * [RevocationList] (#3222, #3745, #3762; spec *Membership and the wire*, Decided 34).
 *
 * Two uses. The id is what lets a device that comes back on a pass be announced as the same device
 * returning ([us.tractat.kuilt.session.ReturningMark.previousId]). The pass is what lets a device back
 * in whose last renewal the host signed and sent but the link lost: the device holds the pass before
 * it, and the host checks the one it signed ([pass]) as it checks a presented one. The record is never
 * the only way in: a device this has forgotten — after a host restart, say — still gets back in on a
 * good pass it presents, and is announced with no previous id.
 *
 * - **Forgotten when the pass is no longer good**, lazily: every read and write first drops the
 *   records whose [Grant.notAfter] is at or before `now`, and those whose pass [revocations] now
 *   voids — a revocation made after the record was written included. A record outlives no pass that
 *   could use it.
 * - **One record per key.** It names the id the latest departure held, so a device with several tabs
 *   that all left is announced under the id that left last. Of their passes it keeps the good one the
 *   host issued last (by [Grant.issuedAt], then the later-ending on a tie), the pass the device's own
 *   store would keep. A revocation voids a device's passes issued before one cutoff, so it can void
 *   the kept pass only by voiding every other one too: a voided pass never displaces a good one, at
 *   the departure or later. The cost: an earlier pass that runs out later than the kept one is not
 *   kept.
 * - **Taken on return** ([take]): a key back in the room is a member again, and its next departure
 *   writes a new record.
 * - **Bounded.** A pass that never expires would keep its record for the host's life, so at most
 *   [capacity] are kept, the oldest departure forgotten first. A forgotten record costs the previous
 *   id on the mark, and a device whose last renewal was lost then comes back on what it holds.
 *
 * Not thread-safe: the room calls it under its own lock. [revocations] takes its own leaf lock.
 */
internal class DepartedMembers(
    private val revocations: RevocationList,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private class Record(val previousId: PeerId, val pass: Grant)

    // Insertion order is the eviction order.
    private val records = LinkedHashMap<DidKey, Record>()

    /**
     * The membership [key] held under [previousId] has left at [now]; [pass] is the last the host
     * signed it. Of [pass] and the one an earlier departure of [key] left, the record keeps the good
     * one issued last, and is dropped if neither is good.
     */
    fun record(key: DidKey, previousId: PeerId, pass: Grant, now: Long) {
        prune(now)
        val earlier = records.remove(key)?.pass
        val kept = listOfNotNull(earlier, pass).filter { it.isGoodAt(now) }.maxWithOrNull(LATER_ISSUED) ?: return
        records[key] = Record(previousId, kept)
        while (records.size > capacity) records.remove(records.keys.first())
    }

    /** The last pass the host signed [key] while a membership of it was here, as kept at [now]; null when none is. */
    fun pass(key: DidKey, now: Long): Grant? {
        prune(now)
        return records[key]?.pass
    }

    /** The id [key] last held, forgetting the record; null when none is remembered at [now]. */
    fun take(key: DidKey, now: Long): PeerId? {
        prune(now)
        return records.remove(key)?.previousId
    }

    /** How many departures are remembered at [now]. */
    fun size(now: Long): Int {
        prune(now)
        return records.size
    }

    fun clear() {
        records.clear()
    }

    private fun prune(now: Long) {
        records.entries.removeAll { (_, record) -> !record.pass.isGoodAt(now) }
    }

    /** Not run out at [now], and not issued before its subject's revocation cutoff, as [GrantTrust] reads both. */
    private fun Grant.isGoodAt(now: Long): Boolean {
        val cutoff = revocations.notBefore(subject)
        return !hasRunOutAt(now) && (cutoff == null || issuedAt >= cutoff)
    }

    companion object {
        /** Past this, a host forgets the oldest departure first. */
        const val DEFAULT_CAPACITY: Int = 256

        private fun Grant.hasRunOutAt(now: Long): Boolean = notAfter != Grant.NO_EXPIRY && notAfter <= now

        /** The pass issued later, then the one that runs out later ([Grant.NO_EXPIRY] is the largest `Long`). */
        private val LATER_ISSUED: Comparator<Grant> = compareBy<Grant>({ it.issuedAt }, { it.notAfter })
    }
}
