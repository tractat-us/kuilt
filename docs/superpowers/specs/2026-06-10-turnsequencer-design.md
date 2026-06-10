# TurnSequencer — a friendly Raft facade for game action sequencing

**Status:** design approved, awaiting implementation
**Issue:** #314
**Module:** new `:kuilt-game` (+ `:kuilt-game-test`)
**Date:** 2026-06-10

## Summary

`TurnSequencer<A, S>` is a thin, game-author-facing facade over `RaftNode`. A
caller `propose(action)`s typed game actions and consumes them back as a
totally-ordered, gap-free stream of typed `Sequenced` events. No Raft concepts
(log indices, terms, snapshots, leader/follower) leak into the game author's
code. It is the intended kuilt entry point for turn-based games.

It lives in a new `:kuilt-game` module so `:kuilt-raft` stays pure consensus and
`:kuilt-game` is the opinionated, game-facing layer (the module the issue
anticipates also housing speculative-rollback).

## Goals

- Typed `propose(action: A)` / `events(): Flow<Sequenced<A, S>>` — bytes never
  surface to the caller.
- A **dense turn ordinal** (`1, 2, 3, …`) that counts only surfaced game
  actions, not the raw Raft log index (which includes withheld no-op and config
  entries).
- **Any peer may propose**, not just the current leader (forward-to-leader).
- **Compaction is invisible**: long sessions and late joiners work via a typed
  state-snapshot lifecycle; the game author thinks in game-state, never bytes.
- Identical ordering on every peer — the property a replay/spectator relies on.

## Non-goals

- Leaderless / multi-leader ordering. Total order over a peer set requires a
  single sequencing funnel at any instant; with `RaftNode` that funnel is the
  elected leader (inherent to Raft, not a kuilt choice). A genuinely leaderless
  sequencer would be a CRDT-based design (`:kuilt-crdt` already has `Rga`),
  trading synchronous commit and a single authoritative order for eventual
  convergence — a different product, out of scope for #314.
- Game rules, move validation, or state transition logic. `TurnSequencer`
  orders opaque-to-it actions; the game gives them meaning.

## Why this shape (the `RaftNode` reality)

The issue's original sketch (`propose` + one `committed: Flow`) is the happy
path. The real `RaftNode` contract forces three refinements:

1. **`committed` is live-only (replay=0); `committedFrom(index)` replays then
   tails.** A late subscriber on `committed` silently misses turn 1. The facade
   exposes a *single* replay-aware entry point to remove that footgun.
2. **The Raft index is not a dense turn counter.** Every election appends a
   withheld no-op; config entries are withheld too. So raft index 5 might be the
   game's turn 3. The facade maintains its own dense counter.
3. **`propose` only works on the leader** (`NotLeaderException` everywhere else).
   "No Raft concepts leak out" means the facade must forward to the leader.
4. **The log can be compacted**, after which a rejoining/lagging peer receives a
   `Committed.Install` reset instead of replaying. The facade turns this into a
   typed state reset.

## Public API

```kotlin
package us.tractat.kuilt.game

/**
 * One ordered event in a TurnSequencer stream — the game-terms analogue of
 * raft's Committed (Entry | Install). A Reset arrives in order relative to the
 * Actions around it, preserving the same in-order guarantee raft gives.
 */
public sealed interface Sequenced<out A, out S> {
    /** The next game action, at a dense turn ordinal (1, 2, 3, …). */
    public data class Action<out A>(val turn: Long, val action: A) : Sequenced<A, Nothing>

    /** The log was compacted: discard local state and reset to [state] as of
     *  [turn], then continue applying Actions above it. (phase C) */
    public data class Reset<out S>(val turn: Long, val state: S) : Sequenced<Nothing, S>
}

public interface TurnSequencer<A, S> {
    /**
     * Proposes [action] and suspends until it is committed and ordered.
     * Any peer may call this; on a follower the action is forwarded to the
     * current leader (phase B). Returns the committed action with its turn.
     */
    public suspend fun propose(action: A): Sequenced.Action<A>

    /**
     * Replays from [from] (default: turn 1), then tails the live stream —
     * gap-free and exactly-once. If [from] was compacted away, a
     * [Sequenced.Reset] is emitted first so the consumer can reset its state
     * before replaying the Actions above the floor.
     */
    public fun events(from: Long = 1L): Flow<Sequenced<A, S>>
}

/**
 * Phase C — supplies state snapshots so the underlying Raft log can compact.
 * [snapshotState] returns the game's current state; the sequencer wraps it in a
 * turn-stamped envelope and publishes it to RaftNode.snapshots on [cadence].
 */
public interface GameSnapshotPolicy<S> {
    public suspend fun snapshotState(): S
    public val cadence: SnapshotCadence   // e.g. every N committed turns
}

/**
 * Construction entry point — a CoroutineScope extension, matching
 * CoroutineScope.raftNode(...). The sequencer owns coroutines (forwarding,
 * snapshot publishing) tied to this scope's lifetime.
 *
 * @param snapshot   phase C; null disables compaction (Install would throw).
 * @param forwarding phase B; null = leader-only (phase A — propose on a
 *                   follower throws NotLeaderException).
 */
public fun <A, S> CoroutineScope.turnSequencer(
    node: RaftNode,
    actions: KSerializer<A>,
    state: KSerializer<S>,
    snapshot: GameSnapshotPolicy<S>? = null,
    forwarding: Forwarding? = null,
): TurnSequencer<A, S>
```

### Design choices baked in

- **Single subscription (`events(from)`)**, no live-only twin — removes the
  late-subscribe-misses-turn-1 footgun.
- **Sealed `Sequenced` stream (Action | Reset)** mirroring `raft.Committed`,
  rather than a side reset-callback — the reset arrives in order relative to
  actions.
- **Dense turn ordinal is deterministic.** Every peer counts only the
  `Committed.Entry` values raft surfaces (no-ops/config already withheld), so all
  peers assign identical turn ordinals with zero coordination.
- **CBOR serialization**, matching `:kuilt-raft` internals and `:kuilt-deal`.

## Phased implementation (A → B → C)

Each phase leaves the build green and is its own PR (one behavior per PR).

### Phase A — pure mapping, leader-only, uncompacted

- `propose`: CBOR-encode → `RaftNode.propose(bytes)`; on a non-leader, the
  `NotLeaderException` propagates (the only phase where a raft concept is
  visible, and it is documented as such). On commit, map `LogEntry.index` to its
  turn ordinal and return `Sequenced.Action`.
- `events(from)`: collect `RaftNode.committedFrom(fromIndex)`, map
  `Committed.Entry → Sequenced.Action(turn, decode(bytes))`, maintaining the
  dense turn counter. `Committed.Install` throws/logs (compaction unsupported).
- `snapshot` and `forwarding` are `null`.
- **Turn↔index translation:** `events` translates the caller's `from: turn` into
  a raft `fromIndex`. Because turn↔index is not 1:1, phase A keeps a small
  in-memory turn→index map built as entries surface; `from = 1` always works
  (the whole retained log). Resuming from an arbitrary not-yet-observed turn is a
  phase-C concern (it needs the snapshot envelope).

### Phase B — forward-to-leader (its own sub-design)

Phase B is the heaviest and least pinned-down piece; it should be treated as its
own design sub-spec (and may warrant its own follow-up issue) rather than
assumed trivial.

- A `Forwarding` channel — a dedicated `Seam` on a Swatch tag distinct from
  raft's own traffic — carries a request/reply protocol:
  `ProposeRequest(correlationId, actionBytes)` → leader proposes →
  `ProposeReply(correlationId, raftIndex)`.
- Routing uses `RaftNode.leader: StateFlow<NodeId?>`. On `null` or a leader
  change mid-flight, retry against the new leader (bounded, with timeout).
- A peer that is itself the leader short-circuits (no network hop).
- **Idempotency is the sharpest correctness question.** Raft `propose` is not
  idempotent across leader changes (mirroring the `LeadershipLostException`
  caveat in `RaftNode`); a retried `ProposeRequest` must commit the action
  exactly once. The correlation id is the dedup key; the exact mechanism is to
  be designed in the phase-B sub-spec.

### Phase C — snapshot lifecycle + reconnect

- `GameSnapshotPolicy<S>` supplies the current state; the sequencer wraps it in a
  turn-stamped envelope `(throughTurn, stateBytes)` and publishes to
  `RaftNode.snapshots` on the configured cadence. The envelope carries the turn
  ordinal so the dense counter survives compaction.
- On `Committed.Install`: unwrap the envelope, reset the turn counter to
  `envelope.throughTurn`, and emit `Sequenced.Reset(turn, decode(state))`.
- `events(from)` now honors an arbitrary `from`: if it falls at/below the
  compaction floor's turn, emit `Reset` first, then replay the `Action`s above
  it — raft's `committedFrom` contract lifted to turns.

## Testing

Test support ships in `:kuilt-game-test`, built on `:kuilt-raft-test`'s
`FakeRaftNode` (which is virtual-time-safe). A `FakeTurnSequencer`/builder lets
consumers unit-test game logic without a real cluster. Coroutine determinism per
repo convention: inject `UnconfinedTestDispatcher(testScheduler)`, never a real
dispatcher under `runTest`.

Per-phase tests:

- **A:** encode/decode round-trip; dense turn ordinals stay `1,2,3…` across
  interleaved no-op/config entries (drive a `FakeRaftNode` that injects a no-op,
  assert no skip); `propose` on a follower throws; `events(1)` replays then tails
  gap-free.
- **B:** follower `propose` forwards and commits; leader short-circuits;
  leader-change mid-propose retries against the new leader; idempotency — a
  retried `ProposeRequest` commits exactly once.
- **C:** snapshot publish → compaction → late `events(from)` gets `Reset` then
  `Action`s; turn counter survives `Install`; reconnect from an arbitrary turn.

`TurnSequencer` is a single concrete facade, not a pluggable contract, so it gets
no TCK of its own — but its tests run against **both** `FakeRaftNode` and a real
in-process `raftNode` cluster (loopback `InMemoryLoom` `Seam`s), so the facade is
verified against the genuine engine, not only the fake.

## Determinism guarantee (to document in KDoc)

Every peer's `events()` yields identical `Action` turn ordinals and identical
`Reset` points. This is the property a game replay or spectator relies on.

## Open items for the implementation plan

- **Phase B forwarding protocol** — the request/reply wire format, leader-change
  retry policy, and idempotency mechanism. Likely its own sub-spec/issue.
- `SnapshotCadence` shape (every-N-turns vs byte/size threshold vs both).
- Exact `Forwarding` construction ergonomics (does the caller pass a `Seam`, a
  `Loom`, or is it derived from the same fabric the `RaftNode` transport uses?).
