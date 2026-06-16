package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A wrapper over [TurnSequencer] that applies local actions optimistically before
 * their committed index arrives, then rolls back and replays if the committed order
 * differs from what was predicted.
 *
 * ## Mechanism
 *
 * On [propose]:
 * 1. The action is applied immediately to [speculativeState] (optimistic apply).
 * 2. The action is appended to a **pending-input buffer**.
 * 3. The underlying [TurnSequencer.propose] is called and suspends until a quorum commits.
 * 4. If propose throws (e.g. [us.tractat.kuilt.raft.LeadershipLostException]), the
 *    speculative apply is rolled back — the action is removed from the pending buffer
 *    and [speculativeState] is restored to the authoritative snapshot + remaining pending.
 *
 * On each [TurnSequencer.committed] emission received by the background collector:
 * - **Match** (committed action equals oldest pending): the pending entry is confirmed
 *   and discarded. The authoritative snapshot advances to include the confirmed action.
 *   No rollback is needed.
 * - **Mismatch** (foreign peer's action, or reorder): the authoritative snapshot advances
 *   to include the committed action. All remaining pending inputs are replayed on top to
 *   produce the new [speculativeState].
 *
 * ## Constraints
 *
 * - [SpeculativeGame.apply] must be **deterministic and pure** — replay correctness depends
 *   on it. See [SpeculativeGame] KDoc.
 * - **No log compaction.** Inherits [TurnSequencer]'s assumption that `Committed.Install`
 *   events are dropped. A snapshot install from Raft would invalidate the pending buffer.
 *   See [SpeculativeGame] for the boundary note.
 * - **Single collector.** The [TurnSequencer.committed] flow must not be collected elsewhere
 *   — the collector backing [speculativeState] is the single consumer of committed events.
 *
 * ## Usage
 *
 * ```kotlin
 * val speculative = SpeculativeSequencer(
 *     sequencer = TurnSequencer(node, Move.serializer()),
 *     game = myGame,          // SpeculativeGame<GameState, Move>
 *     initialState = state0,
 *     scope = viewModelScope, // or a scope tied to session lifetime
 * )
 *
 * // Observe for UI — always up to date with speculative state:
 * speculative.speculativeState.collect { render(it) }
 *
 * // Propose a local player's move (suspends until quorum confirms):
 * try {
 *     speculative.propose(myMove)
 * } catch (e: LeadershipLostException) { /* retry */ }
 * ```
 *
 * @param sequencer The backing [TurnSequencer]. Lifetime is owned by the caller.
 * @param game The consumer-owned state machine. Must be pure and deterministic.
 * @param initialState The authoritative starting state (before any actions).
 * @param scope The [CoroutineScope] that owns the background committed-event collector.
 *   Cancel this scope to stop the collector.
 */
public class SpeculativeSequencer<S, A>(
    private val sequencer: TurnSequencer<A>,
    private val game: SpeculativeGame<S, A>,
    initialState: S,
    scope: CoroutineScope,
) {
    /** Pending inputs submitted locally but not yet confirmed by the committed log. */
    private val pendingBuffer = ArrayDeque<A>()

    /** The last confirmed authoritative state (snapshot after each confirmed commit). */
    private var authoritativeSnapshot: S = game.snapshot(initialState)

    /** The number of committed events processed — used by tests to synchronize assertions. */
    private var confirmedCount = 0

    private val _speculativeState = MutableStateFlow(initialState)

    /**
     * The current speculative game state, including all locally proposed but not-yet-confirmed
     * actions on top of the last authoritative snapshot.
     *
     * Updated on every [propose] (optimistic apply) and on every committed event (confirm or
     * rollback + replay). Suitable for direct observation by a UI layer.
     */
    public val speculativeState: StateFlow<S> = _speculativeState.asStateFlow()

    /**
     * The number of locally proposed actions waiting for confirmation.
     *
     * Exposed as a diagnostic and for test assertions. A count of 0 means the speculative
     * state is fully authoritative — no pending inputs have been applied on top.
     */
    public val pendingCount: Int get() = pendingBuffer.size

    init {
        scope.launch { collectCommitted() }
    }

    /**
     * Proposes [action] for Raft replication and returns after a quorum commits it.
     *
     * Applies [action] optimistically to [speculativeState] **before** suspending, so the
     * UI reflects the move immediately. If the underlying [TurnSequencer.propose] fails,
     * the speculative apply is rolled back — [speculativeState] reverts to the authoritative
     * snapshot plus any remaining pending inputs.
     *
     * @throws [us.tractat.kuilt.raft.LeadershipLostException] if the leader steps down while
     *   awaiting commit. The action is removed from the pending buffer and speculative state
     *   is rolled back. The outcome of the underlying proposal is unknown — the caller must
     *   treat it as lost and retry with an idempotent action or deduplication key.
     */
    public suspend fun propose(action: A): IndexedAction<A> {
        applySpeculatively(action)
        return try {
            sequencer.propose(action)
        } catch (e: Throwable) {
            rollbackSpeculative(action)
            throw e
        }
    }

    // ── Private: speculative apply and rollback ───────────────────────────────

    private fun applySpeculatively(action: A) {
        pendingBuffer.addLast(action)
        _speculativeState.value = game.apply(_speculativeState.value, action)
    }

    private fun rollbackSpeculative(action: A) {
        pendingBuffer.removeLastOrNull()
        _speculativeState.value = replayPendingOnSnapshot()
    }

    // ── Private: committed-event collector ───────────────────────────────────

    private suspend fun collectCommitted() {
        sequencer.committed.collect { indexed -> onCommit(indexed.action) }
    }

    private fun onCommit(committed: A) {
        val oldest = pendingBuffer.firstOrNull()
        if (oldest != null && actionsMatch(oldest, committed)) {
            confirmOldestPending(committed)
        } else {
            applyForeignAndReplay(committed)
        }
        confirmedCount++
    }

    /**
     * Confirms the oldest pending input: it matched the committed action, so we advance
     * the authoritative snapshot without touching the remaining pending entries.
     *
     * The speculative state already reflects this action (it was applied optimistically),
     * so no re-computation is needed — just pop the buffer and advance the snapshot.
     */
    private fun confirmOldestPending(committed: A) {
        pendingBuffer.removeFirst()
        authoritativeSnapshot = game.snapshot(game.apply(authoritativeSnapshot, committed))
        // speculativeState is already correct — no update needed.
    }

    /**
     * Applies a foreign (non-predicted) action to the authoritative snapshot, then
     * replays all remaining pending inputs on top.
     *
     * This is the rollback path: the authoritative snapshot advances, and speculative
     * state is recomputed from scratch by replaying the pending buffer.
     */
    private fun applyForeignAndReplay(foreign: A) {
        authoritativeSnapshot = game.snapshot(game.apply(authoritativeSnapshot, foreign))
        _speculativeState.value = replayPendingOnSnapshot()
    }

    /**
     * Returns the state produced by replaying all pending inputs on top of the
     * current authoritative snapshot.
     */
    private fun replayPendingOnSnapshot(): S =
        pendingBuffer.fold(game.restore(authoritativeSnapshot)) { state, action ->
            game.apply(state, action)
        }

    /**
     * Compares two actions for equality to determine if a commit matches the oldest
     * pending input.
     *
     * Uses structural equality (`==`) which works correctly for data classes. If [A]
     * does not implement meaningful equality, override [SpeculativeGame] to provide a
     * custom comparator — or ensure [A] is a data class or sealed class with `==`.
     */
    private fun actionsMatch(pending: A, committed: A): Boolean = pending == committed

    // ── Test utilities ────────────────────────────────────────────────────────

    /**
     * Suspends until at least [count] committed events have been processed by the
     * background collector. Use in tests to synchronize assertions after pushing
     * commits via [FakeRaftNode.pushCommitted].
     *
     * Not part of the production API — exposed here for test ergonomics rather than
     * a separate test module to keep the surface minimal.
     */
    public suspend fun awaitConfirmedCount(count: Int) {
        while (confirmedCount < count) {
            kotlinx.coroutines.yield()
        }
    }
}
