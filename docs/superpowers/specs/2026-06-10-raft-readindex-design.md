# kuilt-raft ReadIndex: linearizable reads without a log write

**Date:** 2026-06-10
**Status:** approved
**Module:** `:kuilt-raft`
**Issue:** #312 (tracks — not closed by the spec PR)

## Problem

Today the only way to read linearizably is to route the read through
[`RaftNode.propose`][propose] — write the read as a log entry, wait for it to
commit, and observe the result once it surfaces on `committed`. That is correct
but expensive: every read pays a full replication round and grows the log (and
therefore snapshot/compaction pressure) for data that mutates nothing.

ReadIndex (Ongaro thesis §6.4, the "read-only queries" optimisation) serves a
linearizable read **without writing to the log**. The leader confirms it still
holds a voter-quorum at its current term via a heartbeat round, captures the
commit index at that moment as the *read index*, and the read is linearizable
once a state machine has applied through that index.

CheckQuorum (#196) was built as the precondition for exactly this: its design
notes *"a leader that may have silently lost its quorum must not serve reads as
authoritative."* The quorum-freshness signal ReadIndex needs is the same
heartbeat-ACK signal CheckQuorum already counts.

## Scope

**v1 is QuorumRead only** — the safe path that confirms freshness with a real
heartbeat round (~1 RTT). **LeaseRead is out of scope**: trusting a
bounded-clock-drift lease to skip the round is sub-RTT but unsafe under clock
skew, and clock drift is not bounded on the wasmJs/iOS targets in
`kuilt.kmp-library`'s default set. LeaseRead is deferred to a follow-up issue
where the drift-bound contract (and which targets may opt in) can be designed in
isolation. CheckQuorum guarantees only the safe path; v1 adds no new clock
assumptions.

## The external-state-machine constraint

In kuilt-raft the state machine is the **consumer's**, driven by collecting
[`RaftNode.committed`][committed] and tracking its own applied index (see the
`RaftNode` KDoc apply-loop example). The node knows [`commitIndex`][commitIndex]
but **not** the consumer's applied position. So the node *can* confirm a safe
read index, but it cannot itself "suspend until applied ≥ readIndex" — only the
caller knows when its apply loop has caught up.

Therefore `readIndex()` returns the confirmed index; the **caller owns the
apply-catch-up wait**. A small helper bundles the common pattern for callers that
expose their applied index as a flow. This keeps the node honest about a model it
genuinely cannot observe, and adds zero applied-index feedback surface.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Read modes | **QuorumRead only** in v1 | LeaseRead's clock-drift bound is unsafe on wasmJs/iOS; defer to a dedicated issue. CheckQuorum only guarantees the safe path. |
| Apply-wait owner | **Caller waits**; `readIndex()` returns a `Long` | The apply loop is external (the consumer's `committed` collector); the node cannot observe applied index. Returning the index is the only honest contract. |
| Caller convenience | An `awaitRead(applied: StateFlow<Long>)` extension | Common case: caller already tracks applied index as a flow. Helper does `val ri = readIndex(); applied.first { it >= ri }; return ri`. Pure composition over the primitive — no new node state. |
| Wire mechanism | **Batch onto the next heartbeat round; no new `RaftMessage` types** | The quorum-freshness ACK is the heartbeat `AppendEntries` response CheckQuorum already counts at `currentTerm`. Matches CheckQuorum's no-new-wire posture (#196). |
| Concurrency | Concurrent `readIndex()` calls in one window **share one round** | Batching: N readers waiting for the same confirmation resolve off a single heartbeat ACK majority, against the read index captured for that round. |
| Leader-completeness gate (§8) | Block until the current-term no-op commits | A fresh leader doesn't know the true commit index until its `becomeLeader()` no-op commits; serving a read before then risks returning a stale index. The no-op already exists — ReadIndex just waits for it. |
| Single-voter cluster | Return `commitIndex` immediately, no wire round | Quorum is self; freshness is trivially satisfied (mirrors CheckQuorum's `reachable = 0 + 1`). |
| Non-leader behaviour | Throw `NotLeaderException` (default interface impl) | Symmetric with `propose`/`changeMembership`; `FakeRaftNode` inherits the throwing default unchanged. No read-forwarding in v1. |
| Mid-round leadership loss | Fail in-flight reads with `LeadershipLostException` | Symmetric with `propose`; the relinquish path already fails pending proposals — read waiters join that sweep. |
| New wire types | **None** | Leader-local bookkeeping over existing heartbeat responses. |
| `RaftStorage` / `RaftConfig` changes | **None** | Nothing new is persisted; reuses `heartbeatInterval`/election timing. |

## Contract (`RaftNode.kt`)

```kotlin
/**
 * Confirms this leader still holds a voter-quorum at its current term, then returns a
 * **read index**: a commit index `ri` such that any state machine that has applied through
 * `ri` reflects every write committed before this call. The read is linearizable once the
 * caller's apply loop reaches `ri`.
 *
 * The leader confirms freshness via a heartbeat round (it does **not** write to the log).
 * Concurrent calls in the same heartbeat window share one round. A single-voter cluster
 * returns immediately. A freshly-elected leader suspends until its current-term no-op
 * commits (§8) before returning.
 *
 * Because the state machine is external (driven by [committed]), this node cannot observe
 * the caller's applied index — the caller must wait until it has applied through the
 * returned index before serving the read. See [awaitRead] for the common pattern.
 *
 * @return a commit index safe to read at, once applied.
 * @throws NotLeaderException if this node is not the leader (including learners).
 * @throws LeadershipLostException if leadership is lost before the round confirms.
 */
public suspend fun readIndex(): Long {
    throw NotLeaderException("readIndex: not the current leader")
}
```

A default throwing impl on the interface means `FakeRaftNode` in `:kuilt-raft-test`
needs no change (mirrors `changeMembership`).

### Caller helper (`RaftNode.kt`, top-level extension)

```kotlin
/**
 * Linearizable read barrier: confirms the read index via [RaftNode.readIndex], then suspends
 * until [applied] reaches it, returning that index. [applied] is the caller's own monotonic
 * applied-index flow, advanced as it consumes [RaftNode.committed].
 *
 * @throws NotLeaderException / LeadershipLostException as [RaftNode.readIndex].
 */
public suspend fun RaftNode.awaitRead(applied: StateFlow<Long>): Long {
    val ri = readIndex()
    applied.first { it >= ri }
    return ri
}
```

## Engine changes (`internal/RaftEngine.kt`)

### New actor state (leader-only)

```kotlin
// Reads waiting on a quorum-freshness confirmation. Each captures the read index at the
// time it was queued and the heartbeat round it must outlast. Resolved when a voter-quorum
// ACKs at currentTerm in a round >= captured. Failed on any leadership relinquish.
private val pendingReads = mutableListOf<PendingRead>()
// Monotonic per-leadership heartbeat round counter; bumped each heartbeat broadcast.
private var heartbeatRound = 0L
```

`PendingRead` (internal): `(readIndex: Long, sinceRound: Long, deferred: CompletableDeferred<Long>)`.

### New command (`internal/EngineCommand.kt`)

```kotlin
data class RequestReadIndex(val deferred: CompletableDeferred<Long>) : EngineCommand
```

Dispatched in `startActor`'s `when` to `onRequestReadIndex()`.

### Algorithm

```
onRequestReadIndex(deferred):
    if role !is Leader: deferred.completeExceptionally(NotLeaderException(...)); return
    // §8 leader-completeness gate: no-op of currentTerm must be committed.
    if !currentTermNoOpCommitted(): suspend the request until it is, then continue
    val ri = _commitIndex.value
    if clusterConfig.quorumSize == 1:                 // self is the quorum
        deferred.complete(ri); return
    pendingReads += PendingRead(ri, heartbeatRound, deferred)
    forceHeartbeatIfIdle()                            // ensure a round is in flight

onAppendEntriesResponse(from, m):                     // existing CheckQuorum contact recording
    ... record recentVoterContacts += from ...
    resolveReadsIfQuorumFresh()                       // NEW

resolveReadsIfQuorumFresh():
    val reachable = recentVoterContacts.count { it in clusterConfig.voters } + 1
    if reachable < clusterConfig.quorumSize: return
    val now = heartbeatRound
    val (ready, stillWaiting) = pendingReads.partition { now > it.sinceRound }
    pendingReads = stillWaiting
    ready.forEach { emitTrace(ReadIndexConfirmed(it.readIndex, currentTerm)); it.deferred.complete(it.readIndex) }

becomeLeader(): ... pendingReads.clear(); heartbeatRound = 0 ...
relinquishToFollower(reason):
    ... existing failPending(...) ...
    pendingReads.forEach { it.deferred.completeExceptionally(LeadershipLostException("lost leadership before read confirmed")) }
    pendingReads.clear()
```

The `sinceRound` guard ensures a read is only confirmed by ACKs from a heartbeat
round that *started after* the read was queued — an ACK already in flight when
`readIndex()` was called does not prove current-term freshness for this read.
`recentVoterContacts` is the CheckQuorum window set; reuse it directly (it is
already reset each quorum-check tick and only holds `currentTerm` contacts).

## Trace & metrics

- **New `RaftTraceEvent.ReadIndexConfirmed(readIndex: Long, term: Long)`** — emitted
  when a pending read resolves against a fresh quorum. Lets tests assert exactly which
  read index was served and that no log entry was written for it. Follows the existing
  trace-event vocabulary (one event per engine transition).
- No new `RaftMetric` in v1 — add one only if a consumer needs read-rate counting;
  decide during implementation against what `RaftMetric` already exposes.

## Safety argument

- **Linearizability.** The leader serves a read at index `ri = commitIndex` only after
  (a) its current-term no-op has committed — so `commitIndex` reflects all entries
  committed in prior terms (Leader Completeness, §8) — and (b) a voter-quorum has ACKed a
  heartbeat round that *began after* `ri` was captured, proving no other leader has been
  elected in the interim. Any write linearized before the `readIndex()` call has
  `index ≤ ri`, so a state machine applied through `ri` observes it. This is the standard
  §6.4 ReadIndex argument.
- **No safety property is touched.** ReadIndex writes nothing, advances no term, grants no
  vote, and commits no entry. It is pure leader-local bookkeeping over heartbeat responses.
- **No stale read under partition.** A leader partitioned onto the minority side records
  `reachable < quorumSize`, so `resolveReadsIfQuorumFresh` never completes its pending reads
  — they block until CheckQuorum steps the node down, at which point they fail with
  `LeadershipLostException`. A stale leader cannot serve a read.
- **Read-your-writes across a leadership change** is the caller's concern via the returned
  index + apply wait; the node guarantees only that `ri` is linearizable at confirmation time.

## Edge cases

- **Fresh leader, no-op not yet committed:** `readIndex()` suspends until the no-op commits,
  then captures `commitIndex`. Never returns a pre-no-op (possibly stale) index.
- **Single-voter cluster:** returns `commitIndex` immediately, no heartbeat round (quorum is self).
- **Concurrent reads:** all `pendingReads` queued in a window with `sinceRound < now` resolve
  off the same quorum ACK majority — one round serves many readers.
- **Leadership lost mid-round:** `relinquishToFollower` fails every pending read with
  `LeadershipLostException` (same sweep as pending proposals).
- **Learner / follower call:** throws `NotLeaderException` immediately (no round started).
- **Caller never catches up:** `awaitRead` suspends on `applied.first { it >= ri }` until the
  caller's apply loop advances — honours structured concurrency; cancelled with the scope.

## Testing

Use `FakeRaftNode` / the in-memory engine harness and advance virtual time; control flow
rides `delay()`/heartbeat ticks deterministically (per `docs/testing-coroutine-determinism.md`).

1. **Read-your-writes:** propose `x=1` on a 3-voter leader, `readIndex()`, advance until applied
   ≥ returned index; assert the state machine observes `x=1`. Assert **no new log entry** was
   written for the read (log length unchanged; `ReadIndexConfirmed` trace, not an `AppendEntries`).
2. **Non-leader throws:** `readIndex()` on a follower and on a learner both throw `NotLeaderException`.
3. **Fresh-leader gate:** force an election; a `readIndex()` issued before the no-op commits does
   not return until the no-op commits; the returned index ≥ the no-op index.
4. **Concurrency/batching:** launch N concurrent `readIndex()` calls in one heartbeat window;
   assert all resolve against the same read index off a single quorum round (count ACK rounds).
5. **Single-voter:** `readIndex()` on a 1-voter leader returns `commitIndex` with no heartbeat round.
6. **Partition fails reads:** partition the leader from its quorum; in-flight `readIndex()` calls do
   not resolve and fail with `LeadershipLostException` when CheckQuorum steps it down (ties to #196 tests).
7. **`awaitRead` helper:** drive a caller applied-index flow; assert `awaitRead` returns only after
   `applied >= readIndex`, and propagates `NotLeaderException`/`LeadershipLostException`.
8. **Conformance/property (if the harness supports it):** under random partition/heal, every value a
   `readIndex()`-gated read returns is ≥ the index of any write that completed before the call.

## Sequencing & out of scope

- **Depends on CheckQuorum (#196), already merged** — reuses `recentVoterContacts` and the
  heartbeat-ACK contact signal. No conflict beyond `RaftEngine.kt`; rebase onto current `origin/main`.
- **Out of scope:** LeaseRead (own follow-up issue); follower-served / read-forwarding reads;
  applied-index feedback channel; any `RaftConfig`/`RaftStorage`/wire change.
