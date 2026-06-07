# kuilt-raft election robustness: PreVote + §4.2.3 leader-stickiness

**Date:** 2026-06-06  
**Status:** approved  
**Module:** `:kuilt-raft`  
**Issue:** #193 (election robustness). Splits CheckQuorum to #196.

## Problem

A partitioned voter keeps firing election timeouts. Each timeout increments its
term (`onElectionTimeout` → `persistTermAndVote(currentTerm + 1, self)` →
`resetElectionTimeout()`), so its term climbs without bound while it is merely
*unreachable*. On rejoin it carries a much higher term; `onRequestVote` (and the
response handlers) adopt any higher term and step the healthy leader down,
causing perpetual leadership churn — and a node that fell behind can never be
caught up. This is Raft's "disruptive rejoining server" problem (Ongaro thesis
§9.6 / §4.2.3). It was proven during #114: an offline follower could not rejoin
across a compaction boundary because the cluster never stabilised.

The design already anticipated this ("Pre-vote extension — reduces disruptive
elections from partitioned nodes. Worthwhile eventually; not needed for
correctness."). #114 showed it *is* needed for the rejoin path.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scope | PreVote **+** §4.2.3 leader-stickiness | PreVote fixes the term-inflation at its source; stickiness is the cheap, canonical complement (defense in depth). |
| CheckQuorum | **Out** → #196 | Targets a different problem (stale *leadership* belief on a partitioned leader). Our leader can't commit without a quorum anyway. |
| Wire encoding | Distinct `PreVote` / `PreVoteResponse` messages | A separate handler is a *pure read-only predicate* — it structurally cannot mutate term/vote, making the safety property impossible to get wrong. (vs. a `preVote` flag on `RequestVote`, which makes every mutation site a fragile conditional.) |
| "Leader alive" signal | Dedicated leader-lease timer (`electionTimeoutMin`) | Precisely models "heard from a leader within min-election-timeout" — exactly what §4.2.3 specifies; mirrors the existing `electionJob` pattern; deterministic under the test scheduler. |
| PreCandidate phase | Internal only | Public `RaftRole` stays `Follower` until a real election starts — no API noise. |

No `RaftStorage` or `RaftConfig` changes: pre-vote persists nothing and reuses
`electionTimeoutMin`.

## Wire types (`internal/RaftMessage.kt`)

```kotlin
@Serializable
data class PreVote(
    val term: Long,            // PROPOSED term (candidate's currentTerm + 1) — hypothetical
    val candidateId: NodeId,
    val lastLogIndex: Long,
    val lastLogTerm: Long,
) : RaftMessage

@Serializable
data class PreVoteResponse(
    val term: Long,            // granter's REAL currentTerm (lets the candidate detect a higher term)
    val voteGranted: Boolean,
    val proposedTerm: Long,    // echoes the PreVote.term so the candidate matches the round
) : RaftMessage
```

## New actor state (`internal/RaftEngine.kt`)

```kotlin
private var preVoteTerm: Long? = null              // non-null ⇒ a pre-vote round is in flight at this proposed term
private val preVotesGranted = mutableSetOf<NodeId>()
private var leaderAlive = false                     // true while leader, or heard from a leader within electionTimeoutMin
private var leaderLeaseJob: Job? = null
```

## Algorithm

**Probe instead of bumping the term.**
```
onElectionTimeout():
    if role is Leader: return
    _role.value = followerRole                      // a re-timing-out Candidate drops back during the probe
    preVoteTerm = currentTerm + 1
    preVotesGranted = { self }
    resetElectionTimeout()                          // retry the probe if it fails
    if preVotesGranted.size >= quorumSize: startRealElection(); return   // single-voter
    emitTrace(PreVoteStarted)
    for peer in otherVoters: send PreVote(preVoteTerm, self, lastLogIndex, lastLogTerm)
```

**Granting a pre-vote is pure** — it touches no term, no vote, no timer.
```
onPreVote(from, m):
    grant = m.term > currentTerm
            && isLogUpToDate(log.lastOrNull(), m.lastLogIndex, m.lastLogTerm)
            && !leaderAlive
    emitTrace(grant ? PreVoteGranted : PreVoteDenied)
    send(from, PreVoteResponse(currentTerm, grant, m.term))
```

**Tally pre-votes; a real higher term observed in a response is adopted.**
```
onPreVoteResponse(from, m):
    if m.term > currentTerm: stepDown(m.term, HigherTermObserved); return
    if preVoteTerm == null || m.proposedTerm != preVoteTerm: return        // stale / not pre-voting
    if m.voteGranted:
        preVotesGranted += from
        if preVotesGranted.size >= quorumSize: startRealElection()
```

**The real election is unchanged — it is today's `onElectionTimeout` body, now gated.**
```
startRealElection():
    preVoteTerm = null
    persistTermAndVote(currentTerm + 1, self)        // the ONLY term bump
    votesGranted = { self }
    _role.value = Candidate; _leader.value = null
    resetElectionTimeout()
    emitTrace(Timeout)
    if votesGranted.size >= quorumSize: becomeLeader(); return   // single-voter
    for peer in otherVoters: send RequestVote(currentTerm, self, lastLogIndex, lastLogTerm)
```

**§4.2.3 stickiness — `onRequestVote` gains one guard at the top.**
```
onRequestVote(from, m):
    if leaderAlive && m.term > currentTerm:          // protect the live leader; do NOT adopt the term
        emitTrace(VoteDenied(LeaderAlive))
        send(from, RequestVoteResponse(currentTerm, false)); return
    ... existing logic unchanged ...
```

**Leader-contact arms the lease; a pre-vote round is abandoned when a leader appears.**
```
onAppendEntries / onInstallSnapshot (after confirming a valid leader at term >= currentTerm):
    armLeaderLease()
    preVoteTerm = null

armLeaderLease():
    leaderAlive = true
    leaderLeaseJob?.cancel()
    leaderLeaseJob = scope.launch { delay(electionTimeoutMin); leaderAlive = false }

becomeLeader(): leaderAlive = true; leaderLeaseJob?.cancel()   // protect self; drop any stale follower-lease
stepDown(...):  leaderAlive = false; leaderLeaseJob?.cancel()  // stepped down on a higher term — no leader known yet
```

## Safety argument

A partitioned node's peers all have `leaderAlive = true` (they hear the leader), so
the partitioned node wins **zero** pre-votes → never reaches `startRealElection` →
never bumps its term → cannot disrupt on rejoin. Real `RequestVote`s only go out
*after* a pre-vote quorum, which requires a quorum to already agree no leader is
alive — so the §4.2.3 guard practically never blocks a legitimate election. A
partitioned *old* leader on the minority side keeps a stale "I'm leader" belief
(it cannot commit without a quorum) and steps down on first contact with the new
leader; tidying that residual is CheckQuorum's job (#196).

## Edge cases

- **Single-voter:** self-grants its pre-vote → quorum of 1 → `startRealElection` →
  `becomeLeader` — the instant-elect fast path is preserved.
- **Learner:** `resetElectionTimeout` already returns early for learners, so they
  never enter a pre-vote round.
- **Pre-vote round fails to reach quorum** before the election timer fires →
  `onElectionTimeout` runs again, starting a fresh probe at the same `preVoteTerm`
  (`currentTerm` is unchanged). Retries indefinitely with no term growth.
- **A leader appears mid-probe:** valid `AppendEntries`/`InstallSnapshot` clears
  `preVoteTerm` and arms the lease.
- **A real Candidate that times out** falls back to a pre-vote round (no term bump):
  `onElectionTimeout` re-enters the probe and sets the public role back to
  `Follower` until a fresh pre-vote quorum re-promotes it via `startRealElection`.
  This caps term growth even for nodes that lost a real election.

## Trace & persistence

- Add `RaftTraceEvent.PreVoteStarted`, `PreVoteGranted`, `PreVoteDenied`, and a
  `DenyReason.LeaderAlive`. Keeps TLC/debug parity.
- **Persistence is unchanged.** Pre-vote writes nothing; only `startRealElection`
  persists term+vote, exactly as the old election did.

## Testing

- **Canonical (the #114 finding):** partition a voter, heal, assert the leader's
  term is **unchanged** (no churn) and the node rejoins. With PreVote the partitioned
  node no longer inflates its term at all.
- **Interaction with #114/#195:** because `partitionOff` no longer causes term
  inflation once PreVote lands, the original `InstallSnapshotTest` partition scenario
  stops being disruptive. Crash/restart (#195) remains the faithful model for the
  *snapshot* path, but the two are complementary, not competing.
- **Unit:** a candidate does not bump its term until a pre-vote quorum; a granter
  denies a pre-vote while `leaderAlive`; the §4.2.3 guard rejects a higher-term
  `RequestVote` *without* adopting the term; single-voter still elects instantly.
- **Property-based:** Election Safety still holds; a healthy leader is never deposed
  by a partitioned node (term stability under partition+heal).
- **Determinism:** the lease uses `delay()` → the test scheduler; tests advance
  virtual time (`advanceTimeBy(electionTimeoutMin)`), per
  `docs/testing-coroutine-determinism.md`.

## Sequencing & out of scope

- **Sequence after #114 merges** — both edit `RaftEngine`/`RaftMessage`.
- **Out of scope:** CheckQuorum (#196), leadership transfer, the §4.2.3 carve-out
  for an intentional leader handoff (kuilt-raft has no leadership-transfer path).
