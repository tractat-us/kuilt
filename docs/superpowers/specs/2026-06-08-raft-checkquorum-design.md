# kuilt-raft CheckQuorum: a leader steps down when it can't reach a voter-quorum

**Date:** 2026-06-08
**Status:** approved
**Module:** `:kuilt-raft`
**Issue:** #196 (split out of #193 election robustness)

## Problem

Today a leader only relinquishes leadership when it *observes a higher term*
(`stepDown(newTerm, HigherTermObserved)` from a vote/append/snapshot response).
A leader partitioned onto the **minority** side of a network split never sees a
higher term — its peers are unreachable — so it keeps believing it is the leader
indefinitely. It cannot do any *harm* (it can't commit without a quorum, and the
majority side elects a new leader once its followers time out), but its
`role`/`leader` state advertises stale leadership.

The PreVote design (#193) named this exactly: *"A partitioned old leader on the
minority side keeps a stale 'I'm leader' belief (it cannot commit without a
quorum) and steps down on first contact with the new leader; tidying that
residual is CheckQuorum's job (#196)."* This is that tidy-up.

CheckQuorum (Ongaro thesis §6.2): a leader steps down to follower if it has not
received responses from a voter-quorum within an election-timeout window. It
makes leadership state self-correcting under partition rather than waiting for
the partition to heal, and is the precondition for any future leader-lease /
linearizable-read path (a leader that may have silently lost its quorum must not
serve reads as authoritative).

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Step-down term | **Same term**, no bump | CheckQuorum is "I lost contact", not "someone has a newer term". Bumping the term would be gratuitous churn and could disrupt the healthy majority on heal. The node reverts to follower at `currentTerm`; its self-vote for this term is harmless and stays. |
| Contact signal | Any `AppendEntriesResponse` / `InstallSnapshotResponse` at `m.term == currentTerm` | Reachability is the property, not success. A `success=false` log-conflict reply still proves the peer is up and acknowledges our leadership for this term. (A higher-term reply already steps us down via the existing path.) |
| Window mechanism | Windowed **set** of voters heard-from since the last check, reset each tick; tick via `delay()` | Determinism: control flow must ride the test scheduler's virtual clock, not `TimeSource.Monotonic` (which is real-time even under `runTest`). Heartbeats fire every `heartbeatInterval` (≪ election timeout), so each reachable voter responds many times per window — a boundary-clustering false-positive is not reachable for a genuinely-connected peer. Mirrors `heartbeatJob`. |
| Check period | A randomised election timeout (`[electionTimeoutMin, electionTimeoutMax]`), same draw as `resetElectionTimeout` | Symmetric with how a follower decides a leader is dead: the old leader sheds its stale belief on roughly the same timescale the majority needs to elect a replacement. No new config knob. |
| Config flag | **None** — always on | Strictly more correct; matches the no-new-config posture of #193. The behaviour is gated only by cluster size (a 1-voter cluster always self-satisfies quorum). |
| New wire types | **None** | CheckQuorum is leader-local bookkeeping over responses that already exist. Contrast #193, which needed `PreVote`/`PreVoteResponse`. |

No `RaftStorage` changes (nothing new is persisted — a CheckQuorum step-down
writes no term/vote; it is a pure in-memory role transition). No `RaftConfig`
changes (reuses `electionTimeoutMin/Max`).

## New actor state (`internal/RaftEngine.kt`)

```kotlin
// Voters (other than self) from whom a response arrived in the current quorum-check
// window, at currentTerm. Reset every quorum-check tick. Leader-only.
private val recentVoterContacts = mutableSetOf<NodeId>()
private var quorumCheckJob: Job? = null
```

## New command (`internal/EngineCommand.kt`)

```kotlin
data object QuorumCheck : EngineCommand   // periodic leader self-check tick
```

Dispatched in `startActor`'s `when` to `onQuorumCheck()`.

## Algorithm

**Start the self-check when leadership is won; record contact on every response.**
```
becomeLeader():
    ... existing ...
    recentVoterContacts.clear()
    quorumCheckJob?.cancel()
    quorumCheckJob = scope.launch {
        while (true) {
            delay(randomElectionTimeout())     // same draw resetElectionTimeout uses
            cmd.trySend(QuorumCheck)
        }
    }

onAppendEntriesResponse(from, m):
    if m.term > currentTerm: stepDown(...); return
    if role !is Leader || m.term != currentTerm: return
    recentVoterContacts += from                 // ← record reachability (NEW)
    ... existing success/backup logic ...

onInstallSnapshotResponse(from, m):
    ... same guard ...
    recentVoterContacts += from                 // ← record reachability (NEW)
    ... existing ...
```

**Each tick: did a voter-quorum reach us this window? If not, step down.**
```
onQuorumCheck():
    if role !is Leader: return
    val voterContacts = recentVoterContacts.count { it in clusterConfig.voters }
    recentVoterContacts.clear()
    val reachable = voterContacts + 1           // +1 for self (a leader is always a voter)
    if reachable < clusterConfig.quorumSize:
        emitTrace(BecomeFollower(..., LostQuorum))
        stepDownToFollower(LostQuorum)
```

**A same-term step-down: leadership relinquished without a term bump.**
```
stepDownToFollower(reason):                     // factored shared body, see below
    heartbeatJob?.cancel()
    quorumCheckJob?.cancel()
    failPending(LeadershipLostException("lost quorum"))
    snapshotXfer.clear()
    leaderAlive = false                         // can pre-vote / re-elect after timeout
    leaderLeaseJob?.cancel()
    preVoteTerm = null
    _role.value = followerRole
    _leader.value = null
    resetElectionTimeout()
```

### Refactor: extract the relinquish-leadership body

`stepDown(newTerm, reason)` and `stepDownToFollower(reason)` share everything
except the leading `persistTermAndVote(newTerm, null)`. Extract the common tail
into a private helper so the two entry points stay in lockstep:

```kotlin
private suspend fun stepDown(newTerm: Long, reason: StepDownReason) {
    persistTermAndVote(newTerm, null)           // the ONLY difference: adopt the higher term
    relinquishToFollower(reason)
}

private suspend fun stepDownToFollower(reason: StepDownReason) = relinquishToFollower(reason)
```

`relinquishToFollower` is the current `stepDown` body from the leader-cleanup
line onward (heartbeat cancel, fail pending, clear xfer, `leaderAlive=false`,
`leaderLeaseJob.cancel`, `preVoteTerm=null`, candidate-timeout metric, role/leader
reset, trace, `resetElectionTimeout`) **plus** `quorumCheckJob?.cancel()`. Keeping
one body means a future change to step-down can't desync the two callers.

## Trace & metrics

- Add `StepDownReason.LostQuorum` (sits beside `HigherTermObserved`,
  `AppendEntriesFromLeader`). The existing `RaftTraceEvent.BecomeFollower` already
  carries a `StepDownReason`, so no new event type is needed.
- Optional (nice-to-have, low cost): a `RaftMetric.LeadershipLost(term)` or reuse
  of an existing metric so observers can count quorum-loss step-downs. Decide
  during implementation by looking at what `RaftMetric` already exposes; do **not**
  invent a metric if it adds public surface for no consumer.

## Safety argument

- **No safety property is touched.** CheckQuorum only ever moves a node
  Leader→Follower at the *same* term. It never grants a vote, never commits,
  never advances a term. Election Safety / Leader Completeness are unaffected —
  a leader stepping down can only *reduce* the set of nodes claiming leadership.
- **Liveness is preserved.** A leader that can still reach a quorum records ≥
  `quorumSize − 1` peer contacts every window (heartbeats are far more frequent
  than the check period) → `reachable ≥ quorumSize` → never steps down. Only a
  leader that genuinely cannot reach a quorum steps down — which is the desired
  outcome, and the majority side elects a replacement independently.
- **No dual leaders made worse.** Raft already tolerates a stale minority leader
  (it can't commit). CheckQuorum strictly *shortens* the window in which the stale
  leader advertises leadership; it cannot create a new split-brain.
- **Interaction with PreVote (#193):** after stepping down, `leaderAlive=false`
  lets the node run pre-vote probes. On the minority side it wins zero pre-votes
  (no quorum agrees), so — exactly as #193 guarantees — it never bumps its term
  and cannot disrupt the majority on heal.

## Edge cases

- **Single-voter cluster** (`quorumSize == 1`): `reachable = 0 + 1 = 1 ≥ 1` every
  tick → never steps down. Preserved.
- **2-voter cluster** (`quorumSize == 2`): leader must hear from its 1 peer each
  window; partitioned → `reachable = 1 < 2` → steps down. Correct.
- **Learner responses** don't count: the `count { it in clusterConfig.voters }`
  filter excludes them (learners reply to AppendEntries but never vote / count
  toward quorum, consistent with `tryAdvanceLeaderCommit`).
- **First window after election:** `becomeLeader` immediately sends a no-op
  AppendEntries and heartbeats every `heartbeatInterval`; a full election-timeout
  window elapses before the first tick, so responses are collected. `clear()` at
  `becomeLeader` avoids counting pre-leadership cruft.
- **Step-down then immediate re-contact:** if the partition heals right after a
  step-down, an incoming AppendEntries from the (possibly new, possibly same-term)
  leader arms the lease and resets the timer through the normal follower path — no
  special handling.
- **`quorumCheckJob` lifecycle:** started in `becomeLeader`, cancelled in every
  leadership-relinquish path (both `stepDown` variants) and dies with `scope` on
  `close()` — same lifecycle as `heartbeatJob`.

## Testing

Use `FakeRaftNode` / the in-memory engine harness and advance virtual time;
control flow rides `delay()` so `advanceTimeBy(electionTimeout)` is deterministic
(per `docs/testing-coroutine-determinism.md`). The `while(true){delay}` check loop
mirrors `heartbeatJob`, which existing tests already tolerate.

1. **Canonical (the #196 finding):** 3-voter cluster, elect a leader, partition it
   off from the other two. Assert: within ~one election timeout the old leader's
   `role` transitions Leader→Follower (trace `BecomeFollower(LostQuorum)`), its term
   is **unchanged**, and pending `propose()`s fail with `LeadershipLostException`.
2. **No false step-down under health:** a connected leader in a 3-voter cluster
   keeps `role == Leader` across many election-timeout windows (advance virtual
   time well past several check periods).
3. **Single-voter:** a 1-voter cluster leader never steps down (advance time past
   several windows).
4. **`success=false` still counts as contact:** a follower that keeps rejecting
   AppendEntries (log conflict) but is reachable keeps the leader in office — drive
   a scenario where one peer only ever replies `success=false` and assert the
   leader survives (reachability, not success, is the signal).
5. **Heal after step-down:** partition→step-down→heal; the stepped-down node
   rejoins as follower under the new leader without term inflation (PreVote
   interaction — ties to the #193 property tests).
6. **Property/invariant (if the suite has a harness for it):** Election Safety and
   term-stability hold across random partition/heal sequences with CheckQuorum on.

## Sequencing & out of scope

- **Sequence after #228** (pre-vote nonce) lands — both edit `RaftEngine.kt` and
  would otherwise conflict. CheckQuorum adds no wire types, so the only overlap is
  the engine file; rebase onto the post-#228 `origin/main`.
- **Out of scope:** leader leases for linearizable reads (CheckQuorum is a
  precondition, not the feature); leadership transfer; configurable check period;
  any `RaftConfig`/`RaftStorage`/wire change.
