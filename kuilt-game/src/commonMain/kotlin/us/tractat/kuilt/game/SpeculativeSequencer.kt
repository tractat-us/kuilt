package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.Snapshot

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
 * On each [TurnEvent.Committed] emission received by the background collector:
 * - **Duplicate key** (same [DedupKey] as a previously applied entry): the entry is skipped
 *   entirely — the [ClientSessionTable] gate prevents double-apply to the authoritative
 *   snapshot. The confirmed count still advances.
 * - **Match** (committed action equals oldest pending, first-apply): the pending entry is
 *   confirmed and discarded. The authoritative snapshot advances to include the confirmed action.
 *   No rollback is needed.
 * - **Mismatch** (foreign peer's action, or reorder, first-apply): the authoritative snapshot
 *   advances to include the committed action. All remaining pending inputs are replayed on top
 *   to produce the new [speculativeState].
 *
 * ## Constraints
 *
 * - [SpeculativeGame.apply] must be **deterministic and pure** — replay correctness depends
 *   on it. See [SpeculativeGame] KDoc.
 * - **Log compaction rehydrates.** A snapshot install from Raft surfaces as a [TurnEvent.Reset] on
 *   the backing [TurnSequencer.events] stream; the pending buffer is discarded and the authoritative
 *   state is rebuilt via [SpeculativeGame.fromSnapshot] (which must be implemented for compaction-
 *   enabled sessions). See [SpeculativeGame] for the boundary note.
 * - **Single collector.** The [TurnSequencer.events] flow must not be collected elsewhere
 *   — the collector backing [speculativeState] is the single consumer of turn events.
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
 *
 * // Durable propose with a caller-owned request ID for cross-crash exactly-once:
 * try {
 *     speculative.propose(myMove, requestId = nextSerial)
 * } catch (e: LeadershipLostException) { /* retry with same requestId */ }
 * ```
 *
 * @param sequencer The backing [TurnSequencer]. Lifetime is owned by the caller.
 * @param game The consumer-owned state machine. Must be pure and deterministic.
 * @param initialState The authoritative starting state (before any actions).
 * @param scope The [CoroutineScope] that owns the background committed-event collector.
 *   Cancel this scope to stop the collector.
 *
 * @sample us.tractat.kuilt.game.sampleSpeculativeSequencer
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

    /** Internal exactly-once dedup table: prevents double-apply of forwarded/retry duplicates. */
    private val dedupTable = ClientSessionTable()

    private val _confirmedCount = MutableStateFlow(0)

    /**
     * The number of committed events processed by the background collector.
     *
     * Exposed as a [StateFlow] so tests and consumers can suspend until a threshold is reached
     * without busy-waiting. Replaces the prior `while (cond) yield()` spin in [awaitConfirmedCount].
     */
    public val confirmedCount: StateFlow<Int> = _confirmedCount.asStateFlow()

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
            rollbackSpeculative()
            throw e
        }
    }

    /**
     * Proposes [action] with a caller-pinned [requestId] for cross-crash exactly-once, then
     * returns after a quorum commits it (same semantics as [propose]).
     *
     * Mirrors [TurnSequencer.propose]`(action, requestId)`: replay the *same* [requestId] on a
     * post-crash retry and the consumer's [ClientSessionTable] (and this sequencer's internal
     * table) will skip the duplicate. [requestId] must be a per-client monotonic serial the
     * caller owns — do not pass a log index or a random value.
     *
     * @throws [us.tractat.kuilt.raft.LeadershipLostException] if the leader steps down while
     *   awaiting commit. The speculative apply is rolled back. The caller may retry with the
     *   same [requestId].
     */
    public suspend fun propose(action: A, requestId: Long): IndexedAction<A> {
        applySpeculatively(action)
        return try {
            sequencer.propose(action, requestId)
        } catch (e: Throwable) {
            rollbackSpeculative()
            throw e
        }
    }

    // ── Private: speculative apply and rollback ───────────────────────────────

    private fun applySpeculatively(action: A) {
        pendingBuffer.addLast(action)
        _speculativeState.value = game.apply(_speculativeState.value, action)
    }

    private fun rollbackSpeculative() {
        pendingBuffer.removeLastOrNull()
        _speculativeState.value = replayPendingOnSnapshot()
    }

    // ── Private: committed-event collector ───────────────────────────────────

    private suspend fun collectCommitted() {
        sequencer.events.collect { event ->
            when (event) {
                is TurnEvent.Committed -> onCommit(event.indexed)
                is TurnEvent.Reset -> onReset(event.snapshot)
            }
        }
    }

    /**
     * Rehydrates from a Raft snapshot install ([TurnEvent.Reset]).
     *
     * The pending-input buffer is invalidated — the install resets the committed log to a point the
     * buffer may pre-date — so it is **discarded**, and the authoritative state is rebuilt from the
     * snapshot's embedded bytes via [SpeculativeGame.fromSnapshot]. Speculative state then equals the
     * rehydrated snapshot (no pending remains); later commits fold on top.
     *
     * The internal [dedupTable] is left **untouched** (monotonic): clearing it would drop
     * high-water-marks for live clients and let a stale retry re-apply. The residual is the dedup
     * path's existing at-least-once floor — a straggler duplicate of a commit folded into the
     * snapshot but never seen by this lagging node can double-apply, because this node cannot recover
     * the marks embedded in the consumer's opaque snapshot envelope.
     */
    private fun onReset(snapshot: Snapshot) {
        pendingBuffer.clear()
        authoritativeSnapshot = game.snapshot(game.fromSnapshot(snapshot.state))
        _speculativeState.value = replayPendingOnSnapshot()
        _confirmedCount.value++
    }

    /**
     * Processes one committed action, gating **every** commit through the internal exactly-once
     * [dedupTable].
     *
     * Folding every key through [dedupTable] (not just the foreign path) is load-bearing: a
     * locally-proposed action confirmed via the pending buffer must still record its key, otherwise a
     * later forwarded/reconnect duplicate of that same key — which arrives with no matching pending
     * entry and so takes the foreign path — would slip through and **double-apply** to the
     * authoritative snapshot. A `false` result means the key was already applied: drop it. If a
     * duplicate also sits at the head of the pending buffer (a retry under the same key of a
     * still-pending local proposal), pop that stale twin so the buffer doesn't leak and the
     * speculative state stays consistent.
     *
     * Distinct legitimate actions never collide here: the auto-serial [propose] draws a fresh serial
     * per call, so only an explicit same-`requestId` retry shares a key — exactly what dedup drops.
     */
    private fun onCommit(indexed: IndexedAction<A>) {
        val fresh = dedupTable.shouldApply(indexed.dedupKey)
        val oldest = pendingBuffer.firstOrNull()
        val matchesPending = oldest != null && actionsMatch(oldest, indexed.action)
        when {
            !fresh -> if (matchesPending) {
                // Already-applied duplicate that still has a stale pending twin — drop the twin.
                pendingBuffer.removeFirst()
                _speculativeState.value = replayPendingOnSnapshot()
            }
            matchesPending -> confirmOldestPending(indexed.action)
            else -> applyForeignAndReplay(indexed.action)
        }
        _confirmedCount.value++
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
        confirmedCount.first { it >= count }
    }
}
