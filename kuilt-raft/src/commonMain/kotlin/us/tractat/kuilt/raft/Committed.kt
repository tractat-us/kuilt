package us.tractat.kuilt.raft

/**
 * One ordered instruction in the committed stream delivered to a consumer's state machine.
 *
 * A reset ([Install]) always arrives in order relative to the [Entry]s around it: apply nothing with
 * `index <= snapshot.throughIndex` after an install, and miss nothing before it. This in-order
 * guarantee is why the stream is a single sealed type rather than two parallel flows.
 */
public sealed interface Committed {
    /** Apply this committed application entry. */
    public data class Entry(val entry: LogEntry) : Committed
    /** Discard current state and reset the state machine to [snapshot]. Rare — only after a real install. */
    public data class Install(val snapshot: Snapshot) : Committed
}
