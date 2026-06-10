# SpeculativeSequencer — optimistic local apply + rollback over TurnSequencer

**Status:** design approved, awaiting implementation
**Issue:** #315
**Module:** `:kuilt-game` (+ `:kuilt-game-test`)
**Depends on:** `TurnSequencer` (#314 — design approved, awaiting implementation)
**Date:** 2026-06-10

## Summary

`SpeculativeSequencer<A, S>` wraps `TurnSequencer<A, S>` to apply a local action
to a speculative game state **immediately**, before its committed turn arrives,
then reconcile against the authoritative committed order. It eliminates the
perceptible latency of waiting for a full Raft `propose → replicate → commit`
round-trip before the UI updates.

It targets **strict turn-based** games (one legal proposer at any instant — the
peer whose turn it is). In that regime a peer's turn slot is *uncontested*, so
its optimistic action commits in place and a mispredict essentially cannot
occur. The reconciliation machinery is kept correct for the general case but is
optimized and documented for this sweet spot, where the only routinely-exercised
correction is "my proposal failed → undo the optimistic apply."

Lives in `:kuilt-game` alongside `TurnSequencer` — the opinionated, game-facing
layer over pure-consensus `:kuilt-raft`.

## Goals

- `propose(action)` applies `action` to the rendered `state` **synchronously**,
  then orders it via `TurnSequencer`.
- A single authoritative source of truth (the committed stream); the speculative
  view is always *derived*, never the source.
- Reconciliation is one **uniform fold** on every commit — no special-case
  rollback/replay branch.
- Exact correlation of "my optimistic action just committed" with **no
  value-equality guessing** and no propose-return race.
- Speculation is **orthogonal** to `TurnSequencer`'s forwarding (phase B) and
  compaction (phase C): it works against any phase and inherits both
  transparently.

## Non-goals

- **Simultaneous / out-of-turn play as the primary case.** The design stays
  *correct* if a foreign action interleaves with a local pending action, but it
  is not optimized for a high mispredict rate (real-time / GGPO-style P2P
  rollback). Strict turn-based is the documented sweet spot (see #315).
- **Game rules / move validation.** `SpeculativeSequencer` orders and folds
  opaque-to-it actions through the consumer's `reduce`; the game gives them
  meaning. A consumer that wants to reject an illegal move validates it *before*
  calling `propose`.
- **State serialization for rollback.** Immutable `S` makes a "snapshot" a held
  reference and "rollback + replay" a refold; no `saveState`/`restoreState`
  callbacks. `KSerializer<S>` is needed only for Raft compaction, which is
  `TurnSequencer`'s concern.

## Why this shape

The issue sketches GGPO's `saveState()` / `restoreState(snapshot)` /
`AdvanceFrame` callbacks over a mutable game state. Those callbacks exist because
C++ game state is mutated in place. In idiomatic Kotlin the game state is an
**immutable value**, which collapses the whole apparatus:

- A "snapshot" is just holding the last authoritative `S` reference.
- "Rollback to snapshot + replay pending" is just `fold(confirmed, pending)`.
- There is therefore **no distinct rollback code path** — every commit runs the
  same uniform reconciliation step; a mispredict differs only in *cost* (how many
  pending actions are refolded), never in branch.

The single design subtlety GGPO also has is **correlation**: when a commit
arrives, is it the confirmation of my oldest optimistic action, or a foreign
action? We resolve it with a tiny internal **origin tag** rather than fragile
value equality.

## Public API

```kotlin
package us.tractat.kuilt.game

public interface SpeculativeSequencer<A, S> {
    /**
     * The optimistic state to render: [confirmed] folded with the locally-pending
     * actions. Updates synchronously inside [propose] and on every commit.
     */
    public val state: StateFlow<S>

    /**
     * The authoritative state: the fold of committed actions only, in commit
     * order. Lags [state] by the pending actions. Exposed for a "syncing"
     * indicator and for divergence detection; rendering normally uses [state].
     */
    public val confirmed: StateFlow<S>

    /**
     * Applies [action] to [state] optimistically and synchronously, then proposes
     * it for total ordering. Suspends until it commits (confirming the optimistic
     * apply) and returns its dense turn ordinal.
     *
     * If the proposal fails (e.g. leadership lost or timeout), the optimistic
     * apply is undone — the action is removed from the pending buffer and [state]
     * refolded — and the exception is rethrown. The caller's [state] is therefore
     * never left showing an action that will not commit.
     */
    public suspend fun propose(action: A): Long
}

/**
 * Construction entry point — a CoroutineScope extension mirroring
 * `turnSequencer(...)`. The returned sequencer owns a reconciliation coroutine
 * tied to this scope's lifetime; cancelling the scope stops it.
 *
 * @param node      the underlying RaftNode (also supplies this peer's own id,
 *                  used as the origin tag — no separate `self` parameter).
 * @param actions   serializer for game actions A.
 * @param state     serializer for game state S — used only for Raft compaction
 *                  (forwarded to TurnSequencer), never for rollback.
 * @param initial   the game's starting state (turn 0).
 * @param reduce    the deterministic state transition (see Determinism contract).
 * @param snapshot  TurnSequencer phase C; null disables compaction.
 * @param forwarding TurnSequencer phase B; null = leader-only propose.
 */
public fun <A, S> CoroutineScope.speculativeSequencer(
    node: RaftNode,
    actions: KSerializer<A>,
    state: KSerializer<S>,
    initial: S,
    reduce: (S, A) -> S,
    snapshot: GameSnapshotPolicy<S>? = null,
    forwarding: Forwarding? = null,
): SpeculativeSequencer<A, S>
```

### Internal envelope (never visible to the consumer)

```kotlin
@Serializable
internal data class Tagged<A>(val origin: NodeId, val action: A)
```

The layer constructs an internal `TurnSequencer<Tagged<A>, S>` using
`Tagged.serializer(actions)`. On `propose` it wraps the action in
`Tagged(self, action)`; on each committed `Sequenced.Action` it reads
`tagged.origin` to decide *mine* vs *foreign*, then hands `tagged.action` to
`reduce`. `snapshot` and `forwarding` pass straight through to the inner
`TurnSequencer`.

### Design choices baked in

- **Speculative state is derived, authoritative state is the truth.** `state` is
  never written directly except as `fold(confirmed, pending)`.
- **Origin tag, not value equality.** Correlating a confirmation by `origin ==
  self` is exact and survives duplicate action values and the
  propose-return-vs-events-emit race.
- **FIFO pending drain.** A single proposer's actions commit in propose order
  under one leader, so a `self`-tagged commit always confirms `pending.first()`.
- **No `self` parameter.** The origin tag is the node's own id, which Raft
  already holds for elections.

## Reconciliation & data flow

A single coroutine, owned by the receiver `CoroutineScope`, collects
`TurnSequencer.events(from = 1)` and is the **only writer** of `confirmed` and
`state`:

```
propose(action):
    pending += action
    state = fold(confirmed, pending)                  // optimistic, synchronous
    try:
        turn = turnSequencer.propose(Tagged(self, action)).turn
        return turn
    catch e:
        pending -= action                             // undo optimistic apply
        state = fold(confirmed, pending)
        throw e

on Sequenced.Action(turn, tagged):
    confirmed = reduce(confirmed, tagged.action)
    if tagged.origin == self: pending.removeFirst()   // my optimistic action landed
    state = fold(confirmed, pending)

on Sequenced.Reset(turn, snapshotState):              // log compacted under us
    confirmed = snapshotState                         // pending survive (not yet committed)
    state = fold(confirmed, pending)
```

`fold(s, actions) = actions.fold(s, reduce)`.

### Why this is correct and cheap (strict turn-based)

- The proposing peer's turn slot is **uncontested**, so its `Tagged(self, …)`
  commits in place. `pending` is ~length 1 and the refold is trivial. A
  mispredict cannot reorder an uncontested slot.
- A **foreign** action (another peer's turn) commits while this peer's `pending`
  is empty → it advances `confirmed == state` with no visible correction.
- The **rare mispredict path** (a foreign action commits while local `pending`
  is non-empty) is still correct: `confirmed` advances by the foreign action and
  the local optimistic action refolds *on top*; the later `self`-tagged commit
  drains it. The user briefly sees their action re-seated above the foreign one.
- The **only routinely-exercised correction** is **propose failure** → undo. The
  caller's `state` is never left showing an action that will not commit.

### Edge cases

- **propose failure** (`LeadershipLostException`, timeout): undo as above;
  rethrow so the caller knows the move did not take.
- **Concurrent local proposes** (a peer that proposes again before the first
  commits — not the strict turn-based norm): FIFO `pending` handles them; each
  `self`-tagged commit drains the oldest.
- **Reset / compaction**: `pending` are by definition not yet in the committed
  stream, so they survive a `Reset`; only `confirmed` is replaced.
- **Unbounded pending**: in strict turn-based, `pending` is ~1 (act, then wait).
  No hard cap is imposed; the expectation is documented. A consumer that fires
  many un-awaited proposes accepts an O(pending) refold per commit.

## Determinism contract (load-bearing — document in KDoc)

`reduce` must be **pure and deterministic**: the same `(S, A)` yields the same
`S` on every peer. Forbidden inside `reduce`: wall-clock reads, RNG,
environment, IO, or any external mutable state. Any nondeterministic input must
be **carried in the action** (e.g. shuffles/draws produced via `:kuilt-deal`
`FairRandom` and committed as action data), never generated inside `reduce`.

A violation produces **silent cross-peer divergence**: the committed order is
identical on every peer, but folding it yields different states. Speculation
cannot detect this on the happy path — it is a contract the consumer must honor.
`confirmed` is exposed partly so a consumer/test can compare it across peers to
catch a determinism bug.

## Testing

Test support ships in `:kuilt-game-test`, built on `:kuilt-raft-test`'s
virtual-time-safe `FakeRaftNode`. Coroutine determinism per repo convention:
inject `UnconfinedTestDispatcher(testScheduler)`, never a real dispatcher under
`runTest`.

- **Optimistic apply:** `propose` updates `state` synchronously *before* the
  commit arrives; `confirmed` still lags by the pending action.
- **Confirm-in-place:** an uncontested propose → on commit `pending` drains,
  `state == confirmed`, no visible correction.
- **Foreign interleave (mispredict path):** inject a foreign `Action` while local
  `pending` is non-empty → `confirmed` advances, the local optimistic action
  refolds on top, the local commit then drains it. Exercised deterministically.
- **Propose failure:** make `TurnSequencer.propose` throw → optimistic apply is
  undone, `state` reverts to pre-propose, exception rethrown.
- **Reset:** a `Sequenced.Reset` resets `confirmed`, `pending` survive, `state`
  refolds above the reset state.
- **Determinism harness:** two `SpeculativeSequencer`s on one `FakeRaftNode`
  cluster converge to identical `state` and `confirmed` after the same committed
  stream.

`SpeculativeSequencer` is a single concrete facade (no TCK of its own), but its
tests run against **both** `FakeRaftNode` and a real in-process `raftNode`
loopback cluster (`InMemoryLoom` `Seam`s), verifying the facade against the
genuine engine, not only the fake.

## Open items for the implementation plan

- **Phasing relative to `TurnSequencer`.** Speculation needs only phase A of
  `TurnSequencer` (`propose` + `events`) to ship its core; phases B/C are
  inherited transparently. The plan should land speculation's phase-A core first,
  then add B/C-dependent tests once `TurnSequencer` reaches those phases.
- **Propose-failure surface.** Exactly which exceptions `TurnSequencer.propose`
  raises (and which are retryable by forwarding vs. terminal for the caller) is
  pinned down by #314's phase B; the undo path treats any thrown exception as
  terminal for the optimistic apply.
- **Optional `pendingCount` / divergence signal.** Whether to expose a
  `StateFlow<Int>` pending depth (for a "move sent, awaiting confirmation" UI
  affordance) — cheap to add, deferred unless a consumer needs it (YAGNI).
- **Possible future simplification (do not couple #315 to it):** pushing
  proposer-origin *into* `TurnSequencer` (#314) would let spectators/replay see
  "who did what" and remove the `Tagged` envelope here. Tracked as a follow-up,
  not a dependency.
