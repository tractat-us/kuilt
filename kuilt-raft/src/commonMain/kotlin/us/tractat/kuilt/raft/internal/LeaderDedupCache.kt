package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry

/**
 * Best-effort, non-durable leader-side dedup of recently-committed proposals. Confined to the
 * engine's single dispatch loop (no internal locking) and cleared whenever this node loses
 * leadership — a new leader starts cold and the consumer's durable table is the backstop.
 *
 * Keeps **every** keyed entry applied while this node leads, one slot per [DedupKey], with no
 * eviction: it grows with the tenure and is emptied only by [clear]. A lost-ack retry of any key
 * committed this tenure therefore finds its entry, and so does a reused key carrying different
 * bytes, which the engine refuses as `RequestIdConflictException`.
 */
internal class LeaderDedupCache {
    private val lastCommitted = HashMap<DedupKey, LogEntry>()

    /** The recorded result for [key], or null on a miss (or a null/unkeyed proposal). */
    fun lookup(key: DedupKey?): LogEntry? = if (key == null) null else lastCommitted[key]

    /** Records the committed [entry] for [key] so a later retry coalesces. */
    fun record(key: DedupKey?, entry: LogEntry) {
        if (key != null) lastCommitted[key] = entry
    }

    /** Drops all entries — call on every step-down / leadership loss. */
    fun clear(): Unit = lastCommitted.clear()
}
