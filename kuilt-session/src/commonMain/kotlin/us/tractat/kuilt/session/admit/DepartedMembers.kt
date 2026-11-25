package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant

/**
 * **Host only.** For each device key whose membership left, the peer id it held, kept while the last
 * pass the host gave that membership is still good (#3222; spec *Membership and the wire*).
 *
 * It is what lets a device that comes back on its pass be announced as the same device returning
 * ([us.tractat.kuilt.session.ReturningMark.previousId]). It is never what lets the device in: the
 * pass alone does that, so a device this has forgotten — after a host restart, say — is let back in
 * all the same and announced with no previous id.
 *
 * - **Forgotten when the pass runs out**, lazily: every read and write first drops the records whose
 *   [Grant.notAfter] is at or before `now`. A record outlives no pass that could use it.
 * - **One record per key**, the latest departure's. A device with several tabs that all left is
 *   announced under the id that left last.
 * - **Taken on return** ([take]): a key back in the room is a member again, and its next departure
 *   writes a new record.
 * - **Bounded.** A pass that never expires would keep its record for the host's life, so at most
 *   [capacity] are kept, the oldest departure forgotten first. A forgotten record costs only the
 *   previous id on the mark.
 *
 * Not thread-safe: the room calls it under its own lock.
 */
internal class DepartedMembers(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private class Record(val previousId: PeerId, val notAfter: Long)

    // Insertion order is the eviction order.
    private val records = LinkedHashMap<DidKey, Record>()

    /**
     * The membership [key] held under [previousId] has left at [now]; the last pass it was given is
     * good until [notAfter] ([Grant.NO_EXPIRY]: forever). Nothing is kept for a pass already run out.
     */
    fun record(key: DidKey, previousId: PeerId, notAfter: Long, now: Long) {
        prune(now)
        records.remove(key)
        if (notAfter != Grant.NO_EXPIRY && notAfter <= now) return
        records[key] = Record(previousId, notAfter)
        while (records.size > capacity) records.remove(records.keys.first())
    }

    /** The id [key] last held, forgetting it; null when none is remembered at [now]. */
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
        records.entries.removeAll { (_, record) -> record.notAfter != Grant.NO_EXPIRY && record.notAfter <= now }
    }

    companion object {
        /** Past this, a host forgets the oldest departure first. */
        const val DEFAULT_CAPACITY: Int = 256
    }
}
