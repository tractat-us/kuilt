package us.tractat.kuilt.game

/**
 * Consumer-owned description of a deterministic turn-based game, used by
 * [SpeculativeSequencer] to apply, snapshot, and restore game state.
 *
 * The three operations together form the state-management contract that enables
 * optimistic apply with rollback + replay:
 *
 * - **[apply]** drives the game forward one action at a time — used for both
 *   optimistic apply and deterministic replay.
 * - **[snapshot]** captures an authoritative checkpoint after every confirmed
 *   action — the rollback origin.
 * - **[restore]** reinstates a snapshot as the current state — the first step
 *   of a rollback before replay.
 *
 * ## Constraints
 *
 * - **[apply] must be deterministic and pure.** Given the same [state] and [action],
 *   it must always produce an identical successor state. Non-determinism (e.g.
 *   reading a clock or random number) breaks replay correctness.
 * - **[snapshot] must produce an independent copy.** If [S] is mutable, [snapshot]
 *   must deep-copy the state; if [S] is immutable (e.g. a data class), the identity
 *   function `{ it }` suffices.
 * - **Serialization is the caller's responsibility.** [SpeculativeSequencer] does not
 *   serialize [S] — [snapshot] and [restore] are an in-memory contract only.
 *
 * ## Log compaction
 *
 * When Raft installs a compacted snapshot to catch a lagging node up, the install surfaces on
 * [TurnSequencer.events] as a [TurnEvent.Reset]. [SpeculativeSequencer] handles it by
 * **rehydrating**: it discards its pending-input buffer (the install resets the committed log to a
 * point the buffer may pre-date) and rebuilds the authoritative state from the snapshot via
 * [fromSnapshot]. Subsequent committed actions fold on top of the rehydrated baseline. Implement
 * [fromSnapshot] if your session enables log compaction; the default throws, which fails loud the
 * first time a snapshot install arrives rather than silently corrupting state.
 *
 * @param S The game-state type.
 * @param A The action type.
 */
public interface SpeculativeGame<S, A> {
    /**
     * Returns the successor state after applying [action] to [state].
     *
     * Must be **pure and deterministic**: no side effects, no mutable shared state,
     * no I/O. Both optimistic apply and replay call this method — identical inputs
     * must always yield identical outputs.
     */
    public fun apply(state: S, action: A): S

    /**
     * Returns an independent snapshot of [state] that [SpeculativeSequencer] can
     * hold as the authoritative rollback origin.
     *
     * For immutable state types (data classes, value types) the identity function
     * `{ it }` is safe. For mutable containers, return a deep copy.
     */
    public fun snapshot(state: S): S

    /**
     * Returns the game state represented by [snapshot].
     *
     * Called at the start of rollback to reinstate the last authoritative checkpoint
     * before replaying pending inputs on top of it.
     *
     * For immutable state types this is typically the identity function `{ it }`.
     * For mutable containers, return a fresh copy so subsequent mutations don't
     * corrupt the stored snapshot.
     */
    public fun restore(snapshot: S): S

    /**
     * Rebuilds the authoritative game state from a Raft snapshot install's embedded [bytes].
     *
     * Called by [SpeculativeSequencer] on a [TurnEvent.Reset] (log compaction): the sequencer
     * discards its pending buffer and replaces its authoritative state with the value this returns,
     * then folds later committed actions on top.
     *
     * [bytes] is the consumer's **own** snapshot envelope ([TurnEvent.Reset]'s
     * [us.tractat.kuilt.raft.Snapshot.state]) — the game state is typically a *sub-field*, not the
     * whole blob, so extract it rather than decoding [bytes] wholesale as [S].
     *
     * The default throws: a consumer that never enables log compaction need not implement this, and
     * one that does but forgets will **fail loud** the first time a snapshot install arrives rather
     * than silently corrupting state.
     */
    public fun fromSnapshot(bytes: ByteArray): S =
        throw UnsupportedOperationException(
            "This SpeculativeGame does not support log compaction: a TurnEvent.Reset (snapshot " +
                "install) arrived but fromSnapshot() is not implemented. Override fromSnapshot() to " +
                "rebuild state from the snapshot envelope, or disable compaction for this session.",
        )
}
