package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey

/**
 * **Host only.** The peer ids whose seat another id of the same device took over, and who took it
 * (#3159, Task 1.7).
 *
 * When a key's partitioned seat is restored under a new id, the old id is told so directly
 * (Decided 19). That `Farewell` is sent down the link that went quiet, so it is usually lost. The
 * process behind the old id may still be running, and once its link heals its reconnect says Hello
 * again. Challenged, it would get an additional seat of its key — the superseded membership back —
 * so instead the host answers that Hello with the same `Farewell`, which ends the process. This is
 * the memory that answer needs.
 *
 * - **Only the superseded key is answered.** A Hello under the old id naming another key is another
 *   device (a durable peer id whose key store was cleared, Decided 18), and is challenged as usual.
 *   The key a Hello names is unproven, but the answer goes to the Hello's own fabric sender and only
 *   tells it to stop, so a false claim harms only the claimant.
 * - **Bounded.** At most [capacity] ids, the oldest forgotten first. A process superseded longer ago
 *   than that has had every chance to hear the news, and an unbounded record would let a device that
 *   churns peer ids grow host memory without limit. A forgotten id's Hello is admitted again, to an
 *   additional seat — the behaviour before this record existed, and no worse.
 * - **An id seated again is forgotten** ([forget]): from then on its Hellos are a member's.
 *
 * Not thread-safe: the room calls it under its own lock.
 */
internal class SupersededIds(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private class Entry(val key: DidKey, var successor: PeerId)

    // Insertion order is the eviction order.
    private val entries = LinkedHashMap<PeerId, Entry>()

    /** How many ids are remembered. */
    val size: Int get() = entries.size

    /**
     * [previous], under [key], was superseded by [successor]. Any id already pointing at [previous]
     * now points at [successor], so every predecessor is told the membership's current id.
     */
    fun record(previous: PeerId, key: DidKey, successor: PeerId) {
        entries.values.forEach { if (it.successor == previous) it.successor = successor }
        entries.remove(previous)
        entries[previous] = Entry(key, successor)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** The id that took [peerId]'s seat over, if [peerId] was superseded under [key]; else null. */
    fun successorFor(peerId: PeerId, key: DidKey): PeerId? = entries[peerId]?.takeIf { it.key == key }?.successor

    /** [peerId] holds a seat again: stop answering its Hellos for it. */
    fun forget(peerId: PeerId) {
        entries.remove(peerId)
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64
    }
}
