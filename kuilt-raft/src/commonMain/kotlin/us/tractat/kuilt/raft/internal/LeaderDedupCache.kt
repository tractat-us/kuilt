package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry

/**
 * Best-effort, non-durable leader-side dedup of recently-committed proposals. Confined to the
 * engine's single dispatch loop (no internal locking) and cleared whenever this node loses
 * leadership — a new leader starts cold and the consumer's durable table is the backstop.
 *
 * Keeps the most-recent committed entry per client; because serials are monotonic per client, a
 * lost-ack retry is for the most-recent serial, so a single slot per client catches the common case.
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
