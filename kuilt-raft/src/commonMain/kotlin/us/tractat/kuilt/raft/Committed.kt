package us.tractat.kuilt.raft

/**
 * One ordered instruction in the committed stream delivered to a consumer's state machine.
 *
 * A reset ([Install]) always arrives in order relative to the [Entry]s around it: apply nothing with
 * `index <= snapshot.throughIndex` after an install, and miss nothing before it. This in-order
 * guarantee is why the stream is a single sealed type rather than two parallel flows.
 *
 * **Every committed index is accounted for.** An [Entry] carries an application command to apply; an
 * [Internal] marks a committed index whose entry is raft's own bookkeeping and carries no payload at
 * all. Folding both advances a state machine's applied prefix exactly as fast as
 * [RaftNode.commitIndex], which is what makes the applied prefix comparable to
 * [RaftNode.readIndex]'s fence (#1718).
 */
public sealed interface Committed {
    /** Apply this committed application entry. */
    public data class Entry(val entry: LogEntry) : Committed

    /**
     * A committed index raft withheld from the application: the internal §5.4.2 election no-op
     * ([LogEntry.isNoOp]) or a §6 membership-change entry ([LogEntry.config]).
     *
     * Carries [index] and nothing else — deliberately. The entry's contents stay withheld because a
     * consumer must never *apply* raft's own bookkeeping as if it were application data; what the
     * marker restores is only the consumer's ability to say "index [index] is behind me".
     *
     * A state machine folds this by advancing its applied index and doing nothing else:
     *
     * ```kotlin
     * is Committed.Internal -> appliedIndex = committed.index
     * ```
     */
    public data class Internal(val index: Long) : Committed

    /** Discard current state and reset the state machine to [snapshot]. Rare — only after a real install. */
    public data class Install(val snapshot: Snapshot) : Committed
}

/**
 * The [Committed] instruction a committed [LogEntry] surfaces as: an application entry becomes
 * [Committed.Entry]; raft's own bookkeeping — the §5.4.2 election no-op ([LogEntry.isNoOp]) and §6
 * membership-change entries ([LogEntry.config]) — becomes a payload-free [Committed.Internal].
 *
 * This is the withholding rule, in one place. The engine applies it on both the live commit path and
 * the `committedFrom` replay path; a [RaftNode] test double must apply it too, or a consumer's fold
 * passes against the double and stalls against the real engine (#1718).
 */
public fun LogEntry.asCommitted(): Committed =
    if (isNoOp || config != null) Committed.Internal(index) else Committed.Entry(this)
