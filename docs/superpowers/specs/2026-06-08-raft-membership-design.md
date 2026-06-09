# kuilt-raft dynamic membership: joint consensus (§6) over config-in-log

**Date:** 2026-06-08
**Status:** draft
**Module:** `:kuilt-raft`
**Issue:** #194

## Problem

`ClusterConfig` is fixed at construction (`RaftEngine.kt:49` — `private val
clusterConfig`). The voter/learner sets a node believes in never change for the
life of the engine. Any cluster that must **grow** (add a replica), **shrink**
(decommission a node), **replace** a failed machine, or **promote** a learner to
a voter is impossible without tearing the whole cluster down and rebuilding it
from new constructor arguments — which loses the log.

Raft addresses this in two ways:

- **Single-server changes (Ongaro thesis §4.1):** add/remove exactly one voter at
  a time. Any old majority and any new majority overlap by at least one node, so
  C_old → C_new is safe *directly*, no intermediate config. Simpler, but has a
  documented correctness hazard (see "Why joint consensus" below) and only moves
  one voter per round.
- **Joint consensus (original paper §6):** transition through a joint
  configuration C_{old,new} in which agreement requires majorities of **both** the
  old and new voter sets. Arbitrary set changes commit atomically. More moving
  parts, but the safety argument is the original, well-trodden one.

The issue title commits to **§6 joint consensus**, and that is what this design
implements. Single-server changes are explicitly *not* implemented (see
decisions).

## Why joint consensus (not single-server)

Single-server membership changes were published in the thesis with a subtle bug:
if a leader applies a config change as soon as it *appends* the entry (which §4.1
requires for safety) and a second change is started before the first **commits**,
the overlap guarantee can be lost and two disjoint majorities can each elect a
leader. The accepted fix is two extra rules — *(a)* a new leader must commit an
entry from its current term before it may begin a config change, and *(b)* only
one config change may be in flight at a time.

**Joint consensus's safety does not depend on either rule** — it is correct for
*any* C_old→C_new in one transition, because the dual-majority commit rule
(below) means the C_{old,new} entry cannot commit, and therefore C_new cannot be
appended, until both the old and new majorities hold it. Rule (b) we adopt anyway
as a *liveness/simplicity* guard (one transition at a time keeps the state machine
trivial), but it is not load-bearing for safety. Rule (a) is the §5.4.2 hazard
about committing a *prior-term* entry by replica-count; the C_{old,new} entry is
always the leader's *current-term* entry, so `tryAdvanceLeaderCommit`'s existing
`entry.term == currentTerm` gate (`RaftEngine.kt:837`) already protects it — we do
**not** need to (and do not) block `changeMembership` until the election no-op
commits. (An earlier draft claimed the no-op "gives rule (a) for free"; that claim
was wrong — `becomeLeader`'s `appendNoOp` only *appends*, it does not block on
commit — and is unnecessary, so it is dropped rather than fixed.) For a
craft/learning codebase the §6 path is the one worth building: it is the canonical,
fully-general construction.

## Core model

### Configuration as a log entry, adopted on **append**

Membership lives **in the replicated log**, not in a constructor field. A change
is two ordinary log entries:

1. **C_{old,new}** — the *joint* configuration. Agreement (commit + election)
   now requires majorities of the old voter set **and** the new voter set,
   independently.
2. **C_new** — the *final* configuration, appended by the leader only after
   C_{old,new} has committed. Once C_new commits, the transition is done.

The cardinal §6 rule: **a node uses a configuration the moment it appends that
entry to its log, regardless of whether the entry is committed.** A leader
operating in the joint phase counts dual majorities even though C_{old,new} is
not yet committed; a follower that receives C_{old,new} adopts it immediately.
This is what makes the transition safe across leader changes mid-flight.

Consequence: the effective configuration can be **rolled back**. If a follower
appended C_{old,new} from a leader that then crashed, and a new leader overwrites
that log suffix, the follower must revert to whatever configuration the surviving
prefix implies. So the effective config is always a **pure function of the log +
snapshot**, recomputed whenever the log tail changes — never mutated ad hoc.

### `MembershipState` — the effective configuration

```kotlin
/** The configuration a node is currently operating under. */
internal sealed interface MembershipState {
    /** A single configuration: quorum = majority(config.voters). */
    data class Simple(val config: ClusterConfig) : MembershipState
    /** Joint C_{old,new}: quorum = majority(old.voters) AND majority(new.voters). */
    data class Joint(val old: ClusterConfig, val new: ClusterConfig) : MembershipState
}
```

Quorum, election, replication, and CheckQuorum all consult `MembershipState`
instead of a flat `ClusterConfig`:

- **Replication target set** = every node mentioned by either side
  (`Simple`: `config.allMembers`; `Joint`: `old.allMembers ∪ new.allMembers`),
  minus self. The leader replicates to all of them (including soon-to-be-removed
  nodes — they still vote in the old majority during the joint phase).
- **Commit** (`majorityCommitIndex`): `Simple` → as today. `Joint` → an index is
  committed only if it has a majority in `old.voters` **and** a majority in
  `new.voters`.
- **Election vote count** (`onRequestVoteResponse`, `onPreVoteResponse`):
  `Simple` → `votes ≥ quorumSize`. `Joint` → granted by a majority of `old.voters`
  **and** a majority of `new.voters`.
- **CheckQuorum** (`onQuorumCheck`): leader steps down unless it heard from a
  majority of `old.voters` **and** (in joint) a majority of `new.voters` this
  window.

### The config payload on a `LogEntry`

Config entries are raft-internal, exactly like the election no-op: they are
replicated and persisted but **withheld from `RaftNode.committed`** (they carry no
application data). Today the only "internal" marker is `isNoOp`
(`LogEntry.kt:28`). We add a config payload:

```kotlin
@Serializable
public data class LogEntry(
    val index: Long,
    val term: Long,
    val command: ByteArray,
    val isNoOp: Boolean = false,
    val config: ConfigPayload? = null,   // NEW — non-null ⇒ this is a config entry
)

/** Serialized membership change carried by a config log entry. */
@Serializable
public data class ConfigPayload(
    val old: ClusterConfig?,   // non-null only for the joint C_{old,new} entry
    val new: ClusterConfig,
)
```

`config != null` ⇒ internal entry, withheld from `committed` (same gate as
`isNoOp` at `RaftEngine.kt:849`). `ConfigPayload(old = X, new = Y)` ⇒ `Joint`;
`ConfigPayload(old = null, new = Y)` ⇒ `Simple(Y)`. (`ClusterConfig` becomes
`@Serializable`; it is already a plain data class of `Set<NodeId>`.)

`LogEntry`'s **hand-written `equals`/`hashCode`** (`LogEntry.kt:30-39`) must be
extended to include `config` — otherwise two entries differing only in their config
payload compare equal, corrupting conflict detection and tests. CBOR serialization
of `ClusterConfig` is over `Set<NodeId>`; the learner-set-only fast-path comparison
(`target.voters == membership.currentVoters`) is **value equality of the sets**, not
a byte comparison, so set iteration order is irrelevant there.

### Bootstrap configuration

A fresh cluster has no config entries in its log. The configuration passed to
`raftNode(clusterConfig = …)` remains the **bootstrap** config: the effective
config when neither the log nor the snapshot carries one. The recompute function
is:

```
effectiveMembership =
    latest config entry in log (highest index), else
    snapshot's config (see below), else
    Simple(bootstrapConfig)
```

So a cluster that never changes membership behaves **identically to today** — no
config entries are ever written, `effectiveMembership` is always
`Simple(bootstrapConfig)`. This is the backward-compatibility anchor.

### Snapshots must carry the configuration (#114 interaction)

A snapshot replaces a log prefix. If membership changed within that prefix, a
node installing the snapshot would otherwise lose the change. So the snapshot
metadata gains the effective config at `lastIncludedIndex`:

- `SnapshotMeta` (`RaftStorage.kt:4`) gains `config: ConfigPayload` (joint-capable
  — a snapshot *can* be taken mid-transition).
- `RaftMessage.InstallSnapshot` (`RaftMessage.kt:50`) gains the same field.
- On install, the node adopts the snapshot's config as the base for its recompute
  (it becomes the "else snapshot's config" branch above).
- The outbound `Snapshot` the *consumer* publishes (`RaftNode.snapshots`) stays
  opaque application bytes — the config travels in `SnapshotMeta`/the wire frame,
  set by the engine, never by the consumer.

## Public API

One leader-only method, mirroring `propose` (`RaftEngine.kt:929`):

```kotlin
public suspend fun changeMembership(target: ClusterConfig): ClusterConfig
```

- Leader-only; throws `NotLeaderException` off the leader (same as `propose`).
- Suspends until the change **commits as C_new**, then returns `target`. Fails the
  awaiting deferred if leadership is lost mid-transition
  (`LeadershipLostException`) or the change is rejected.
- Throws `MembershipChangeInProgressException` if a config entry is already
  uncommitted (the one-change-at-a-time rule).
- Throws `IllegalArgumentException` if `target.voters` is empty (a cluster must
  have at least one voter).

There is **no** separate `addLearner` / `removeServer` / `promoteToVoter`.
`changeMembership(target)` is fully general; the learner-catch-up pattern is a
usage convention (below), not extra surface — matching the no-new-config posture
of #193/#196.

### Recommended usage: learner-first catch-up

Adding a brand-new voter directly into a joint config briefly lowers fault
tolerance (the new node has an empty log; until it catches up the new majority is
hard to reach). The §6-recommended pattern, expressible with the single API:

1. `changeMembership(current + new node **as learner**)` — a **learner-set-only**
   change (see "Two change classes"); no quorum impact, node catches up via normal
   replication.
2. Wait for the learner to catch up (observe its `committed`/matchIndex, or just a
   heuristic delay — caller's choice; the library does not block on catch-up).
3. `changeMembership(target with the node moved to voters)` — a **voter-set**
   change via joint consensus.

## Two change classes (the safety split)

The dispatch inside `changeMembership` splits on **whether the voter set
changes**, because that is exactly what decides whether a majority is affected:

| Change class | Example | Mechanism | Why safe |
|---|---|---|---|
| **Learner-set only** (voters unchanged) | add/remove a learner, demote a voter *that is being removed entirely*? no — see note | A single `Simple(target)` config entry | Learners are in no majority (`majorityCommitIndex` uses `otherVoters` only — `RaftEngine.kt:834`; elections use `otherVoters` — `RaftEngine.kt:394`). Changing the learner set cannot change any quorum, so no joint phase is needed. |
| **Voter-set change** (voters differ) | grow 3→5, shrink 5→3, replace a voter, promote learner→voter | Two entries: `Joint(old=current, new=target)` then `Simple(target)` | Standard §6 dual-majority argument. |

Note: any change that *moves a node between the voter and learner sets* is a
voter-set change (the voter set differs) and therefore goes through joint
consensus. Only pure learner add/remove with the voter set byte-identical takes
the simple path.

This split is principled — "does it affect a majority?" — not an arbitrary
phasing, and it lets the foundation land (learner changes) before the harder
joint-quorum math.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Membership algorithm | **Joint consensus (§6)** only; no single-server fast path | Canonical, fully general, avoids the §4.1 overlap-bug surface. Matches the issue title. |
| Config storage | **In the log**, adopted on append, recomputed as a pure function of log+snapshot | The §6 safety requirement; makes mid-flight leader change correct. |
| Entry marking | New nullable `LogEntry.config: ConfigPayload?` (joint-capable) | Mirrors `isNoOp`'s "internal, withheld from `committed`" treatment; nullable keeps the field absent (and the wire/persist format unchanged) for the overwhelmingly common application entry. |
| Public API | One `changeMembership(target)`, leader-only, suspends to C_new commit | Minimal surface; learner catch-up is a usage pattern, not new methods. |
| Learner-set changes | Single `Simple` entry, **no joint phase** | They touch no majority — joint consensus would be ceremony with no safety value. |
| Catch-up | **Caller's responsibility**; library does not block on it | The library can't know the caller's staleness tolerance; exposing matchIndex/committed is enough. Keeps `changeMembership` non-magical. |
| One change at a time | Reject `changeMembership` while any config entry is uncommitted | Liveness/simplicity guard (not load-bearing for §6 safety); cheap leader-local flag. |
| Rule (a) "commit a current-term entry first" | **Not enforced — not needed.** The C_{old,new} entry is the leader's current-term entry, protected by `tryAdvanceLeaderCommit`'s `entry.term == currentTerm` gate (`RaftEngine.kt:837`); joint-consensus safety doesn't rest on rule (a) | The election no-op is appended but not committed when `changeMembership` may run, so it could **not** provide this guarantee — and §6 doesn't require it. |
| Removed leader | Leader **steps down after C_new commits** if it is not in `C_new.voters` | §6.4.1. It must shepherd the transition (it owns the joint entries) until C_new is durable, *then* relinquish. CheckQuorum (#196) is the backstop if it is partitioned out earlier. |
| Removed-server disruption | Handled by **PreVote (#193)** — no new mechanism | A node not in the new config (or a slow removed node) cannot win pre-votes from a majority that still hears from the leader (`DenyReason.LeaderAlive`), so it never inflates the term. §6.4's "ignore RequestVote near a heartbeat" is what PreVote already does. |
| Snapshot config | `SnapshotMeta` + `InstallSnapshot` carry `ConfigPayload` | A snapshot replaces a prefix that may contain a config change; the installer must learn the membership. |
| `ClusterConfig` serializability | Add `@Serializable` | Needed to ride in `ConfigPayload` (log + wire + snapshot meta). |

## Engine changes (`internal/RaftEngine.kt`)

`clusterConfig: ClusterConfig` (the immutable ctor field) is **kept as
`bootstrapConfig`** and a new derived field is introduced:

```kotlin
// Effective membership = pure function of (log, snapshot, bootstrapConfig).
// Recomputed by recomputeMembership() on every append/truncate/install.
private var membership: MembershipState = MembershipState.Simple(bootstrapConfig)
```

All the `clusterConfig.*` read sites from the investigation are redirected:

| Site (current) | Now consults |
|---|---|
| `otherVoters` (`:126`) | `membership.electionTargets(self)` — union of `old.voters ∪ new.voters` in joint, minus self (RequestVote recipients) |
| `otherMembers` (`:130`) | `membership.replicationTargets(self)` — union of all members both sides, minus self |
| `followerRole` (`:283`) learner check | `membership.isLearner(self)`; **and `recomputeMembership` re-evaluates `_role.value` for self** so a promoted learner→voter actually leaves `Learner` and becomes election-eligible (and a demoted node enters `Learner`) |
| election quorum (`:365,:391,:432,:462`) | `membership.voterQuorumReached(votesGranted, self)` — `Simple`: majority; `Joint`: dual majority; self credited per-set |
| `onQuorumCheck` (`:557,:560`) | `membership.quorumOfContacts(recentVoterContacts, self)` — dual in joint; replaces the hardcoded `+1` with per-voter-set self-credit |
| `tryAdvanceLeaderCommit` (`:832-835`) | `membership.committedIndex(matchIndex, lastLogIndex, self)` — dual in joint; self credited only when `self ∈` the relevant voter set |

New helpers on `MembershipState` encapsulate the `Simple`/`Joint` branch so the
engine never special-cases inline. **Every helper takes `self` and credits the
leader toward a voter set only when `self ∈ that set`** — this is the load-bearing
correction for the removed-leader case (a leader driving C_new to commit while it
is itself *not* a voter in C_new must not count itself):

```kotlin
fun voterQuorumReached(grantingVoters: Set<NodeId>, self: NodeId): Boolean
fun committedIndex(matchIndex: Map<NodeId, Long>, leaderLastIndex: Long, self: NodeId): Long?
fun quorumOfContacts(contactedVoters: Set<NodeId>, self: NodeId): Boolean   // CheckQuorum
fun replicationTargets(self: NodeId): Set<NodeId>
fun electionTargets(self: NodeId): Set<NodeId>
fun currentVoters: Set<NodeId>          // Simple → config.voters; Joint → new.voters (the target)
fun isVoter(id: NodeId): Boolean
fun isLearner(id: NodeId): Boolean
```

`majorityCommitIndex` (`RaftLogMath.kt:68`) is **revised** so the leader's
implicit self-match is conditional, not unconditional. Today it bakes in
`peerQuorum = quorumSize - 1` and always credits the leader via `leaderLastIndex`;
that over-counts by one whenever `self ∉ voters` and would commit an entry a
replica short of a real majority. The primitive becomes "given a voter set, the
acks on those voters, and whether the leader is one of them, return the highest
index a majority of the set holds":

```kotlin
internal fun majorityIndexOf(
    voters: Set<NodeId>,
    matchIndex: Map<NodeId, Long>,
    leaderLastIndex: Long,
    leaderIsVoter: Boolean,
): Long?
```

`Simple.committedIndex` calls it once for `config.voters`; `Joint.committedIndex`
calls it for `old.voters` and `new.voters` and returns the **min** of the two (an
index is committed only when *both* majorities hold it). `voterQuorumReached` and
`quorumOfContacts` apply the same per-set `self`-credit for election and
CheckQuorum respectively.

### The change-membership deferred (BLOCKER: not on `pending`)

Config entries are internal, so they are deliberately **not** placed on the
`pending` map (`RaftEngine.kt:271`) — which means the existing `failPending`
(called from `relinquishToFollower` and the actor `finally`) will **never** fail a
`changeMembership` caller. Without a dedicated path, a caller hangs forever if the
leader loses leadership mid-transition (the leader-crash-mid-joint case). So:

```kotlin
// At most one in flight (one-change-at-a-time). Completed on C_new commit,
// failed on any leadership relinquish / close.
private var pendingConfigChange: CompletableDeferred<ClusterConfig>? = null
private var configChangeTargetVoters: Set<NodeId>? = null   // distinguishes simple vs joint completion
```

`relinquishToFollower` and the actor `finally` must, alongside `failPending`,
do `pendingConfigChange?.completeExceptionally(LeadershipLostException(...))` and
null both fields. The one-change-at-a-time guard is simply
`pendingConfigChange != null`.

### `changeMembership` flow (leader)

```
onChangeMembership(target, deferred):
    if role !is Leader: deferred.fail(NotLeaderException); return
    if pendingConfigChange != null: deferred.fail(MembershipChangeInProgressException); return
    if target.voters.isEmpty(): deferred.fail(IllegalArgumentException); return

    pendingConfigChange = deferred
    configChangeTargetVoters = target.voters
    if target.voters == membership.currentVoters:        // learner-set-only
        appendConfigEntry(ConfigPayload(old = null, new = target))          // Simple
    else:                                                # voter-set change → joint
        appendConfigEntry(ConfigPayload(old = currentEffectiveConfig, new = target))  // Joint
```

`currentEffectiveConfig` for the joint `old` is the `Simple` config the node is
operating under (the precondition `pendingConfigChange == null` guarantees
`membership is Simple` at this point — a joint config can only exist while a change
is in flight).

### Config-entry commit hook — **runs after the apply loop, never inside it**

(BLOCKER: `advanceCommit`'s loop holds a stale `currentCommitIndex` until line
856, and `entryAt` linear-scans the very `log` we'd be mutating.) Collect a flag
during the loop; run all transition side effects **after** `currentCommitIndex =
newCommit`:

```
advanceCommit(newCommit):
    committedAConfigEntry = null
    for idx in (currentCommitIndex+1)..newCommit:
        entry = entryAt(idx) ?: continue
        if entry.config != null: committedAConfigEntry = entry      // NOTE: withheld from _committed
        else if !entry.isNoOp: emit to _committed; complete pending
        _commitIndex.value = idx
    currentCommitIndex = newCommit                                  // ← bump first
    if committedAConfigEntry != null: onConfigCommitted(committedAConfigEntry)   // ← then side effects

onConfigCommitted(entry):
    if entry is Joint (config.old != null):
        appendConfigEntry(ConfigPayload(old = null, new = entry.config.new))   // append C_new
        # appendConfigEntry replicates; in a fully-caught-up cluster its
        # tryAdvanceLeaderCommit may immediately re-commit C_new → re-enter
        # onConfigCommitted for the Simple case. Safe: we're past the loop, commit
        # index is current, recursion depth ≤ 2.
    else:   # Simple(new) committed → transition complete
        pendingConfigChange?.complete(ClusterConfig(new.voters, new.learners))
        pendingConfigChange = null; configChangeTargetVoters = null
        if self !in new.voters: stepDownToFollower(RemovedFromConfig)   # §6.4.1
```

`appendConfigEntry` is `onPropose`'s body specialized for a config entry: append,
persist, **`recomputeMembership()` immediately** (adopt-on-append), trace
(`ConfigChange`), replicate via `replicationTargets`, then `tryAdvanceLeaderCommit`
(needed for the single-voter / already-caught-up cases). It does **not** touch
`pending`/`_committed`.

### Adopt-on-append + rollback

```
recomputeMembership():
    membership =
        (highest-index config entry in the in-memory log)?.toMembershipState()
        ?: snapshotConfig?.toMembershipState()
        ?: Simple(bootstrapConfig)
    reevaluateSelfRole()   // promoted learner→voter leaves Learner; demoted enters it
```

Called after: `appendConfigEntry` append (leader), `onAppendEntries`
append-**and**-truncate (a follower: call it after the conflicting-suffix truncate
at `RaftEngine.kt:786` *and* after the append, so both rollback and adoption are
captured), and snapshot install. Because it is a pure recompute from current log
state, truncation rollback is automatic — no separate undo path.

**Two config entries in the live log is normal, not an error.** Every voter change
puts both the `Joint` and the trailing `Simple(new)` in the log at once;
"highest-index" correctly resolves to whichever was appended last (adopt-on-append).
Do not assert uniqueness anywhere.

**Snapshot config must be the effective config *as of `lastIncludedIndex`*, not the
node's current `membership`.** When `onCompact` (`RaftEngine.kt:895`) writes
`SnapshotMeta`, it must record the membership implied by entries `≤ throughIndex`
(the last config entry at or below `throughIndex`, else the prior snapshot's config,
else bootstrap) — which can differ from the live `membership` if a config entry sits
between `throughIndex` and the log tail. Computing it from live `membership` would
stamp the snapshot with a *future* config and corrupt an installer's view.

### Trace & errors

- `StepDownReason.RemovedFromConfig` (beside `HigherTermObserved`,
  `AppendEntriesFromLeader`, `LostQuorum`).
- `RaftTraceEvent.ConfigChange(clock, node, index, old, new)` — emitted when a
  config entry is appended (adopt-on-append), so tests assert membership
  transitions through `trace` (term/config are otherwise private).
- New exceptions in `RaftExceptions.kt`:
  `MembershipChangeInProgressException`. (`LeadershipLostException`,
  `NotLeaderException` already exist.)

## Safety argument

- **Election Safety / Leader Completeness preserved.** In the joint phase, a
  candidate needs *both* an old-majority and a new-majority. Any leader elected in
  C_old and any leader elected in C_{old,new} share an old-majority member, and any
  C_{old,new} and C_new leaders share a new-majority member — the standard §6
  chain. No window exists where two disjoint majorities can each elect, because
  the only configurations any node ever uses are C_old, C_{old,new}, C_new in log
  order, and C_{old,new} bridges the two single configs.
- **Commitment is dual in the joint phase**, so the C_{old,new} entry itself
  cannot commit (and thus C_new cannot be appended) until both majorities have it
  — closing the gap the single-server approach left open.
- **Adopt-on-append, recompute-from-log** guarantees a node never operates under a
  configuration that isn't justified by its current log, including after a
  conflicting-suffix truncation.
- **One change at a time** means at most one of {C_{old,new}, C_new} is in flight;
  there is never a second joint config layered on the first.
- **Removed leader** keeps serving only until C_new is durable on the new majority,
  then steps down — it cannot strand the transition, and it cannot keep leading a
  cluster it is no longer part of. CheckQuorum is the backstop.
- **Disruption from removed/learner nodes** is bounded by PreVote: they cannot
  bump the cluster term without a majority pre-vote, which a healthy majority
  denies via `LeaderAlive`.

## Edge cases

- **Single-voter bootstrap → add a second voter.** `Joint({A},{A,B})`: old
  majority = {A}, new majority = needs 2 of {A,B}. The entry commits only once B
  has it *and* A has it — i.e. B must be caught up. Correct (this is why
  learner-first catch-up matters). A naive direct add would otherwise be
  un-committable until B catches up, blocking — acceptable but slow; the doc steers
  callers to learner-first.
- **Remove the leader (3→2 dropping the leader).** Leader appends Joint, then
  C_new (without itself), shepherds C_new to commit on the 2 survivors, then steps
  down `RemovedFromConfig`. The survivors elect among themselves.
- **Leader crash mid-joint.** A new leader is elected under whatever config its log
  justifies. If it has C_{old,new}, it continues the transition (its commit of
  C_{old,new} triggers the C_new append). If it only has C_old, the change is
  effectively aborted and the caller's deferred fails with leadership loss — the
  caller retries. No unsafe state.
- **Snapshot taken mid-joint.** `SnapshotMeta.config` carries the joint payload; an
  installer resumes in the joint phase. Rare but must be representable — hence
  `ConfigPayload` (not flat `ClusterConfig`) in the snapshot.
- **Learner add/remove during a voter change.** Rejected by the
  one-change-at-a-time guard (a config entry is uncommitted) — caller serializes.
- **`changeMembership(currentConfig)` (no-op target).** Voter set equal, learner
  set equal → a `Simple` entry identical to the current effective config; harmless,
  commits immediately, returns. (Could short-circuit, but the entry is cheap and
  the uniform path is simpler.)
- **Empty voters.** Rejected at the API (`IllegalArgumentException`).
- **Removed voter after C_new commits (§6.4 disruption).** Once C_new is in force
  the removed node leaves the leader's `replicationTargets`, so it stops receiving
  AppendEntries; its leader lease lapses and it begins PreVote probes. It wins zero
  pre-votes (no majority grants — the healthy majority still hears the leader and
  denies via `LeaderAlive`), so it never inflates the cluster term and cannot
  disrupt. Bounded by PreVote (#193); no new mechanism.
- **Restart recovery.** On construction a node replays its persisted log + snapshot;
  `recomputeMembership` runs over that state, so a node that crashed mid-transition
  comes back under exactly the config its durable log justifies — no special restart
  path. (Verify `recomputeMembership` is invoked during the engine's
  log-load/init, not only on live appends.)

## Testing

Use the in-memory sim harness (`InMemoryRaftNetwork`, `raftSim`, `partitionOff`,
virtual-time advance) and assert through `trace` (config/term are private), per
`PreVoteTest.kt`/`CheckQuorumTest.kt`. Coroutine determinism per
`docs/testing-coroutine-determinism.md`.

1. **Learner add/remove (simple path):** add a learner via `changeMembership`,
   assert it receives `committed` entries and is excluded from quorum (commit still
   needs only the original voter majority); remove it, assert no replication.
2. **Grow 3→5 (joint):** start 3 voters, add 2 learners, catch them up,
   `changeMembership` to 5 voters. Assert: trace shows `Joint` then `Simple(C_new)`;
   after C_new commits, a partition that leaves any 3 of the 5 can still elect/commit
   and any 2 cannot.
3. **Shrink 5→3:** symmetric; removed voters stop counting; assert old 3-of-5
   majority no longer commits once C_new is in force.
4. **Remove the leader:** assert leader steps down `RemovedFromConfig` after C_new
   commits and the survivors elect a new leader.
5. **One-change-at-a-time:** a second `changeMembership` while the first is
   uncommitted throws `MembershipChangeInProgressException`.
6. **Adopt-on-append / rollback:** craft a follower that appends C_{old,new} from a
   doomed leader, then a new leader overwrites the suffix; assert the follower's
   effective membership rolls back (via a forced election outcome that only the
   rolled-back config permits).
7. **Snapshot carries config:** change membership, compact past the change, install
   the snapshot on a fresh node; assert it adopts the post-change membership.
8. **Leader crash mid-joint:** kill the leader after Joint but before C_new; assert
   a safe outcome (either a new leader completes the transition or it aborts cleanly;
   no split-brain) and the caller's deferred fails when its leader dies.
9. **Property/chaos (extend `ChaosTest`/PBT):** random membership churn interleaved
   with partition/heal; assert Election Safety, Leader Completeness, and "no two
   leaders at the same term" hold throughout. `log()` what is / isn't covered.

## Sequencing & out of scope

- **Stack of PRs** (see the plan): (A) config-in-log + snapshot foundation +
  learner-set changes (simple path); (B) joint-consensus voter changes + removed-
  leader step-down; (C) invariant/chaos coverage. Each rebases onto the prior.
- **Out of scope:** single-server (§4.1) changes; leadership transfer (§3.10) — a
  natural companion to "remove the leader" but a separate feature; automatic
  catch-up gating inside `changeMembership` (caller's job); configurable joint-phase
  timeouts (reuses existing election/heartbeat config).
