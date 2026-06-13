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
 * [SpeculativeSequencer] inherits [TurnSequencer]'s no-compaction assumption:
 * [TurnSequencer.committed] drops `Committed.Install` snapshot installs. A Raft
 * snapshot install would invalidate the pending-input buffer (the install resets the
 * committed log to a point the buffer may pre-date). Supporting snapshot installs
 * is out of scope here; if log compaction is enabled, the pending buffer must be
 * cleared and [restore] called with the install's embedded state at that point.
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
}
