package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant

/**
 * **Host only.** For each device key whose membership left, the peer id it held and the last pass the
 * host signed it, kept while that pass is still good (#3222, #3745; spec *Membership and the wire*,
 * Decided 34).
 *
 * Two uses. The id is what lets a device that comes back on a pass be announced as the same device
 * returning ([us.tractat.kuilt.session.ReturningMark.previousId]). The pass is what lets a device back
 * in whose last renewal the host signed and sent but the link lost: the device holds the pass before
 * it, and the host checks the one it signed ([pass]) as it checks a presented one. The record is never
 * the only way in: a device this has forgotten — after a host restart, say — still gets back in on a
 * good pass it presents, and is announced with no previous id.
 *
 * - **Forgotten when the pass runs out**, lazily: every read and write first drops the records whose
 *   [Grant.notAfter] is at or before `now`. A record outlives no pass that could use it.
 * - **One record per key.** It names the id the latest departure held, so a device with several tabs
 *   that all left is announced under the id that left last, and it keeps whichever of their passes
 *   runs out later, so a tab's departure never shortens what an earlier one left.
 * - **Taken on return** ([take]): a key back in the room is a member again, and its next departure
 *   writes a new record.
 * - **Bounded.** A pass that never expires would keep its record for the host's life, so at most
 *   [capacity] are kept, the oldest departure forgotten first. A forgotten record costs the previous
 *   id on the mark, and a device whose last renewal was lost then comes back on what it holds.
 *
 * Not thread-safe: the room calls it under its own lock.
 */
internal class DepartedMembers(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private class Record(val previousId: PeerId, val pass: Grant)

    // Insertion order is the eviction order.
    private val records = LinkedHashMap<DidKey, Record>()

    /**
     * The membership [key] held under [previousId] has left at [now]; [pass] is the last the host
     * signed it. The record keeps the later-ending of [pass] and the one an earlier departure of [key]
     * left, and is dropped if that one has already run out.
     */
    fun record(key: DidKey, previousId: PeerId, pass: Grant, now: Long) {
        prune(now)
        val kept = records.remove(key)?.pass?.let { earlier -> laterEnding(earlier, pass) } ?: pass
        if (kept.hasRunOutAt(now)) return
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
        records.entries.removeAll { (_, record) -> record.pass.hasRunOutAt(now) }
    }

    companion object {
        /** Past this, a host forgets the oldest departure first. */
        const val DEFAULT_CAPACITY: Int = 256

        private fun Grant.hasRunOutAt(now: Long): Boolean = notAfter != Grant.NO_EXPIRY && notAfter <= now

        /** Of two passes, the one that runs out later ([Grant.NO_EXPIRY] is the largest `Long`); [first] on a tie. */
        private fun laterEnding(first: Grant, second: Grant): Grant = if (second.notAfter > first.notAfter) second else first
    }
}
