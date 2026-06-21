package us.tractat.kuilt.game

import us.tractat.kuilt.raft.Snapshot

/**
 * One instruction in a [TurnSequencer]'s in-order [TurnSequencer.events] stream.
 *
 * A single sealed stream — rather than two parallel flows — so a [Reset] always arrives in order
 * relative to the [Committed] actions around it: apply nothing already covered by the reset
 * snapshot, and miss nothing committed before it.
 *
 * @param A the application action type.
 */
public sealed interface TurnEvent<out A> {
    /**
     * A committed application action, carrying its assigned log [IndexedAction.index] and the
     * proposer-stamped [IndexedAction.dedupKey]. Apply [indexed] to the game state machine, folding
     * the dedup key through a [us.tractat.kuilt.raft.ClientSessionTable] to preserve exactly-once.
     */
    public data class Committed<out A>(val indexed: IndexedAction<A>) : TurnEvent<A>

    /**
     * The state machine must discard its current state and reset to [snapshot] — Raft installed a
     * compacted snapshot to catch this lagging node up. Rare: only when log compaction is enabled.
     *
     * The consumer extracts its own state from a *sub-field* of [Snapshot.state] (its own snapshot
     * envelope), including the [us.tractat.kuilt.raft.ClientSessionTable] high-water-marks it
     * serialized in — it must **not** treat the whole [Snapshot.state] as the table bytes.
     */
    public data class Reset(val snapshot: Snapshot) : TurnEvent<Nothing>
}
