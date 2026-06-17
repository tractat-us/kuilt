# Design: leader-forwarding for `RaftNode.propose`

**Date:** 2026-06-16
**Epic:** [#474](https://github.com/tractat-us/kuilt/issues/474) (docs & examples — audience-first rewrite)
**Status:** draft, for review

## Problem

`RaftNode.propose(command)` only works on the leader. Called on a follower it
throws `NotLeaderException`; the node merely exposes a `leader: NodeId?` so the
caller can manually re-send. `TurnSequencer.propose` inherits this and rethrows
it as `NotYourTurnException`.

This surfaced while building the two-player tic-tac-toe + chat example
([#479](https://github.com/tractat-us/kuilt/issues/479)): in a peer-symmetric
session every peer *is* a Raft node, but only one is leader, so a non-leader
player cannot get its move into the log at all. The current workaround would be
to abuse Raft leadership as a turn token (transfer leadership each turn) — which
conflates two orthogonal concepts:

- **Raft leadership** = log-ordering authority. Infrastructure. Stays on one
  stable peer for the whole game.
- **Turn ownership** = the game-semantic right to make the next move. Alternates
  every move; derived from the committed log; **not** Raft's concern.

## Goal

Any peer can call `propose` and have its command land in the agreed log,
regardless of who holds Raft leadership — by **forwarding** the command to the
current leader over the existing Raft transport. The leader remains the single
appender (this is Raft's defining property; it is the source of the total
order). Forwarding only automates the redirect Raft already mandates (Raft
paper §8, client interaction) so the caller no longer has to error out and
re-send.

Non-goals (deliberately out of scope, per design discussion):

- **No turn API.** Turn ownership / "is it my turn" stays in application code,
  which watches the committed game state. The library keeps knowing nothing
  about application semantics. This keeps `TurnSequencer` general enough for
  repeated-turn, real-time, and strict-alternation games alike.
- **No exactly-once / client-session dedup** in v1 (see Deferred).

## Scope of change

Forwarding is **internal to `kuilt-raft`**. The discovery that makes this small:

- `RaftMessage` (the wire RPC set) is an **internal sealed interface**; the
  transport (`RaftTransport.sendTo(peer, bytes)`) carries opaque bytes and the
  engine (de)serialises `RaftMessage` itself.
- Therefore the public `RaftTransport` SPI and `SeamRaftTransport` **do not
  change**. No fabric needs touching.

Public surface that *does* change:

- `RaftNode.propose` — behaviour + KDoc: now succeeds from any node.
- `NotLeaderException` — no longer thrown merely because "this node is a
  follower"; reserved for terminal cases (node closed). A follower with no
  leader yet **waits** (see Behaviour decisions), so absence of a leader is not
  an error.
- `TurnSequencer` — `NotYourTurnException` becomes vestigial and is **removed**.
  It was the original mis-naming (Raft leadership dressed up as a game turn);
  with forwarding there is no "not your turn" at the Raft layer.

## Design

### New internal messages (`RaftMessage`)

```
Forward(clientRequestId: Long, command: ByteArray)            // follower -> leader
ForwardResponse(clientRequestId: Long, outcome: ForwardOutcome) // leader -> follower
  ForwardOutcome = Committed(index: Long, term: Long) | NotLeader | Failed
```

`clientRequestId` is a per-node monotonic nonce, used only to correlate the
response to the awaiting `propose` call. It is **not** written into the log, so
log entries and what `committed` delivers are unchanged.

### Follower `propose(command)`

1. Allocate a `clientRequestId`; register a `CompletableDeferred<LogEntry>`.
2. Determine the current leader. If none is known yet (election in flight),
   **wait** until one is, cancellably (see Behaviour decisions).
3. `sendTo(leader, Forward(id, command))`.
4. On `ForwardResponse(id, Committed(index, term))` → complete with
   `LogEntry(index, term, command)` and return it. Suspends until commit, same
   contract as the leader path.
5. On `NotLeader` / `Failed` / leader change / transport drop → fail the call
   with a **retryable** exception (`LeadershipLostException`). v1 does **not**
   auto-retry, so no command is ever appended twice. The caller may retry.

### Leader handling of `Forward`

1. Receive `Forward(id, command)` from peer `p`.
2. Run the normal local propose path (append, await quorum commit).
3. On success → `sendTo(p, ForwardResponse(id, Committed(index, term)))`.
4. If it stepped down mid-flight (local propose throws `LeadershipLostException`)
   → `sendTo(p, ForwardResponse(id, NotLeader))`.

### Why no duplicates in v1

A duplicate could only arise from auto-retry across a leader change (old leader
commits, then forwarder retries to a new leader). v1 does not auto-retry, so the
worst case is a *failed* `propose` the caller re-issues deliberately — never a
silent double-append. Safe auto-retry needs §8 client-serial dedup (Deferred).

## The example it unblocks ([#479](https://github.com/tractat-us/kuilt/issues/479))

Two-player tic-tac-toe **with chat**, played to a result, in `:examples`:

- Two peers over `InMemoryLoom`; `MuxSeam` (raft = channel 0, chat = channel 1).
- Real `RaftNode` + `TurnSequencer` per peer; one stable Raft leader.
- Each peer, in its own coroutine: collect `committed` → apply `Move` to a local
  3×3 board → derive whose turn it is from the move count (even = X/alice,
  odd = O/bob) → **the player to move calls `propose`** (forwarding lets the
  non-leader peer move). Moves are scripted so the run is deterministic and X
  wins a line.
- Each peer detects **win or draw** from its own board (deterministic ⇒ both
  agree) and stops.
- Chat: `Rga<String>` over `SeamReplicator` on channel 1; both peers post.
- Assertions: both peers converge on the **same move sequence**, the **same
  final board**, the **same outcome**, and the **same chat log**.

Win/draw, the board, and turn derivation are **example code** — application
semantics the library does not own.

## Testing

- **Forwarding unit test (`kuilt-raft`):** a small in-memory multi-node cluster
  (real `RaftEngine` + in-memory transport, as `LeadershipTransferTest` etc.
  already do) where a **follower** calls `propose` and the entry commits on
  every node. Cover: follower propose commits; leader-change-mid-forward yields
  a retryable failure (no double-append).
- **Determinism:** `StandardTestDispatcher` + bounded time-advance per the repo
  coroutine-determinism rules. No real dispatchers, no wall-clock waits.
- **Example integration test** as described above.

## Behaviour decisions

**No-leader behaviour of a follower `propose`: wait (cancellable).** When no
leader is known yet (election in flight), `propose` suspends until a leader
emerges, then forwards — bounded only by the caller's `withTimeout` /
cancellation. One clean contract: *propose suspends until the command commits or
you cancel.* Absence of a leader is a transient state it waits through, never an
error.

## Deferred (follow-ups, not this work)

- **Exactly-once forwarding** via §8 client session + serial-number dedup at the
  leader, enabling safe auto-retry across leader changes.
- **Construction-boilerplate facade** ([#480](https://github.com/tractat-us/kuilt/issues/480),
  narrowed): hide `ClusterConfig`/transport/storage wiring and `MuxSeam`
  ergonomics for the common case. (Its turn-facade idea is dropped — turns are
  application semantics.)

## Epic bookkeeping

- New sub-issue: forwarding in `kuilt-raft` (this spec).
- Example work: reopened [#479](https://github.com/tractat-us/kuilt/issues/479).
- [#480](https://github.com/tractat-us/kuilt/issues/480): narrow to the
  construction facade; drop the turn-facade idea.
- Nothing merges until the interface is validated; all draft.
