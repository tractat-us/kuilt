# RaftEngine decomposition — design pass for #1121 (DEF2)

**Target:** `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt` (1892 lines as of `bugs-idle` @ 3f5fb442).
**Issue:** tractat-us/kuilt#1121 (`needs-design`), deferred from cleanup epic #1079 as DEF2.
**Status:** APPROVED 2026-07-06 by Iain — the seam order and the five-machine campaign are cleared for execution. Approved decisions (see "Resolved decisions" below): (1) `RaftState` = shared core only (10 fields), (2) full depth — all six PRs incl. `ReadIndexTracker`/`ProposalForwarder`, (3) decision-returning machines, (4) snapshot sender/receiver as two PRs. One PR-5-time detail (`heartbeatRound` placement) deferred.

## Context and governing rules

`RaftEngine` is the single-actor implementation behind the public `RaftNode` interface: one
coroutine drains `cmd: Channel<EngineCommand>` (`:86`, loop at `:370-403`) and every mutable
field is confined to that actor. The file spans five concerns — election/pre-vote, log
replication + commit, chunked InstallSnapshot transfer (§7), dynamic membership (§6), and
leadership transfer (§3.10) — plus client-serial dedup and propose-forwarding (§8). It is
core consensus code AND the hottest file in the repo, so the decomposition runs under hard
rules:

1. **Design and reviews on the strong model; mechanical edits on workers.** Every PR gets an
   opus `code-reviewer` pass before auto-merge. Workers are dispatched `general-purpose` with
   `isolation: "worktree"`, one PR per worker, strictly sequential (every PR rewrites large
   spans of the same file — never parallelize within this plan).
2. **One behavior-move per PR.** Each PR relocates exactly one concern with ZERO behavior
   change. No opportunistic cleanups, renames beyond the move, or "while I'm here" fixes.
3. **HOT file — rebase onto `origin/main` immediately before opening each PR** (and
   `git fetch origin main && grep` the target region before starting work — the
   duplicate-fix collision of #1165/#1074 is the failure mode).
4. **Verification through the canonical sim harness only** — `RaftSimulation` +
   `InMemoryRaftNetwork` + `raftRunTest` in `kuilt-raft/src/commonTest/`. Never a hand-rolled
   cluster, never `advanceUntilIdle()`, never a widened timeout (repo CLAUDE.md, "Multi-node /
   consensus tests run through the canonical simulation harness").
5. **Merge gate per PR:** `timeout 600 ./gradlew :kuilt-raft:build detektAll --rerun-tasks`
   locally (full module build — `jvmTest` alone misses Android/Native variant compiles), then
   CI `ci-required` green.

### Landed fixes the moves must PRESERVE (do not regress)

The DEF2 stub was written before Phase 0/1/2 of #1079 executed. The small RaftEngine fixes it
warned about have since **merged** — the extraction PRs must carry them verbatim, and a
reviewer should check each survived the move:

| Landed fix | PR | What to preserve (current lines) |
|---|---|---|
| B5 prevTerm fail-fast | #1127 (2772d237) | `error("prevTerm for in-window index …")` instead of `?: 0L` in `sendAppendEntries` (`:856-860`) |
| P2 `!!` hoists | #1134 (756bef10) | hoisted `val transferInFlight = transferTarget` (`:1366`, `:1647`) and `requireNotNull(leaderId)` in `flushWaitingForLeader` (`:1844`) |
| R1 O(1) dedup + side-effect-free completion | #1152 (633b619d) | `val have = log.mapTo(HashSet()) { it.index }` (`:1088-1089`); `matches`/`removeAll(matches)`/`complete` split (`:1231-1233`) |
| matchIndex clamp | #1176 (f18bfd9a) | `minOf(m.matchIndex, lastLogIndex)` in `onAppendEntriesResponse` (`:1127`) |
| Declaration-before-init ordering | #1192 (6673667b) | teardown-touched fields declared BEFORE the `init` block (`:312-324` comment citing #1077) — **binding on every new machine field this plan introduces** |

## Field inventory (~35 mutable pieces of actor state)

All fields below are **actor-confined**: mutated only from inside the actor loop's message
handlers (or the `init` restore coroutine at `:326-368`, which runs strictly before
`startActor()` in the same launch — sequential happens-before). Cross-thread reads happen
only through the `StateFlow`/`SharedFlow` surfaces (`_role`, `_leader`, `_commitIndex`,
`_membership`, `_committed`, `snapshots`, `_compactionFloor`, `_trace`), which are proper
thread-safe primitives and are NOT part of this inventory. Timer jobs interact with the actor
solely via `cmd.trySend(...)` — the sanctioned single-dedicated-writer pattern (repo
CLAUDE.md thread-safety rule: a Channel-draining actor is a legitimate primitive, unlike
`limitedParallelism(1)` confinement).

### Election / term

| Field (line) | Type | Written by | Read by | Invariant |
|---|---|---|---|---|
| `currentTerm` (:191) | `var Long` | `persistTermAndVote` (:408), init restore (:329) | everywhere | monotonically non-decreasing; storage persisted BEFORE in-memory update |
| `votedFor` (:192) | `var NodeId?` | `persistTermAndVote`, `persistVote` (:415), init | `onRequestVote` (:612) | at most one grant per term; persisted-first |
| `votesGranted` (:201) | `MutableSet<NodeId>` | `startRealElection` clear+seed (:576-577), `onRequestVoteResponse` (:632) | quorum check (:633) | meaningful only while Candidate at `currentTerm` |
| `preVoteTerm` (:204) | `var Long?` | set `onElectionTimeout` (:555); cleared `startRealElection` (:569), `relinquishToFollower` (:772), `onAppendEntries` (:1031), `onInstallSnapshot` (:965) | `onPreVoteResponse` (:660) | non-null iff a pre-vote probe is in flight |
| `preVoteRound` (:205) | `var Long` | bumped `onElectionTimeout` (:556) | round-nonce match (:660) | monotonic; stale-response rejection nonce |
| `preVotesGranted` (:206) | `MutableSet<NodeId>` | `onElectionTimeout`, `onPreVoteResponse` | quorum check (:663) | valid only for current `preVoteTerm`+`preVoteRound` |
| `leaderAlive` (:278) | `var Boolean` | `armLeaderLease` (:539), `LeaseExpired` (:381), `relinquishToFollower` (:770), `becomeLeader` (:676) | vote/pre-vote stickiness (:605, :644) | true ⇒ reject higher-term (pre-)votes (§4.2.3), except the transfer target |
| `electionStartTime` (:307) | `var ValueTimeMark?` | `startRealElection`, `becomeLeader`, `relinquishToFollower` | metrics only | non-null iff an election of ours is unresolved |
| `electionStartTerm` (:310) | `var Long` | `startRealElection` (:583) | `ElectionTimedOut` metric | pairs with `electionStartTime` |

### Log / commit / membership (the shared core → **RaftState** in PR-1)

| Field (line) | Type | Written by | Read by | Invariant |
|---|---|---|---|---|
| `log` (:193) | `MutableList<LogEntry>` | `onPropose` (:1377), `appendNoOp` (:720), `appendConfigEntry` (:1511), `onAppendEntries` truncate/append (:1083, :1091), `finalizeInstalledSnapshot` (:998, :1001), `onCompact` (:1321), init (:331) | everywhere | contiguous ascending indexes starting at `snapshotIndex + 1`; every append mirrored to `storage` |
| `currentCommitIndex` (:194) | `var Long` | `advanceCommit` (:1235), init/install seeds (:344, :1006) | commit gates, reads | monotonic; ≥ `snapshotIndex`; mirrored to `_commitIndex` flow |
| `snapshotIndex` (:197) | `var Long` | init (:337), `finalizeInstalledSnapshot` (:1003), `onCompact` (:1322) | log math, AE floor check (:1046) | `log` never contains index ≤ it; the covered prefix is committed |
| `snapshotTerm` (:198) | `var Long` | same three sites | `prevTerm` at boundary (:857), `lastLogTerm` (:488) | term of the entry at `snapshotIndex` |
| `snapshotConfig` (:172) | `var ConfigPayload?` | init (:341), `onCompact` (:1326), `finalizeInstalledSnapshot` (:1014) | `recomputeMembership` (:1444) | config baseline of the snapshot in force; null when none |
| `membershipState` (:163) | `var MembershipState` | `recomputeMembership` ONLY (:1456) | quorum math everywhere | pure function of (log, snapshotConfig, bootstrapConfig); never mutated ad hoc |
| `nextIndex` (:209) | `MutableMap<NodeId, Long>` | `becomeLeader` seed (:680), AE resp (:1128, :1134), snapshot complete (:949) | `sendAppendEntries` (:849) | ≥ 1; ≤ `lastLogIndex + 1` (#1175 clamp); leader-only |
| `matchIndex` (:210) | `MutableMap<NodeId, Long>` | `becomeLeader` (:681), AE resp (:1127), snapshot complete (:948) | `tryAdvanceLeaderCommit` (:1195), transfer readiness (:1679, :1697) | per-peer monotonic within a term; ≤ `lastLogIndex` |

### Snapshot transfer — leader side (→ **SnapshotSender** in PR-2)

| Field (line) | Type | Written by | Read by | Invariant |
|---|---|---|---|---|
| `SnapshotXfer` (:221) + `snapshotXfer` (:222) | `MutableMap<NodeId, SnapshotXfer>` | `sendSnapshotChunk` create (:909), `onInstallSnapshotResponse` advance/remove (:946-950), `relinquishToFollower` clear (:757) | `sendSnapshotChunk`, `onInstallSnapshotResponse` | leader-only; ≤ 1 transfer per peer; one chunk in flight per peer (await-ack-then-next); `nextOffset` ≤ `state.size` |

### Snapshot transfer — follower side (→ **SnapshotReceiver** in PR-3)

| Field (line) | Type | Written by | Read by | Invariant |
|---|---|---|---|---|
| `SnapshotReassembly` (:225) + `incomingSnapshot` (:226) | `var SnapshotReassembly?` | `onInstallSnapshot` create at offset 0 (:971), null on mismatch (:975), cleared after install (:1017) | chunk-accept logic (:973) | `buffer.size` == next expected offset; `meta` constant across a reassembly; discarded whole on any mismatch |

### Leadership transfer (→ **LeadershipTransferMachine** in PR-4)

| Field (line) | Type | Written by | Read by | Invariant |
|---|---|---|---|---|
| `transferTarget` (:287) | `var NodeId?` | `onTransferLeadership` (:1652), `failPendingTransfer`/`completePendingTransfer` (:452-467), `becomeLeader` clear (:706) | propose gate (:1366), vote-stickiness exception (:604), `sendTimeoutNowIfReady` (:1695) | non-null iff a transfer is in flight; while set, `onPropose` rejects with `NotLeaderException` |
| `transferDeferred` (:293) | `var CompletableDeferred<Unit>?` | same sites | completion paths | **all-or-none with `transferTarget` and `transferTimeoutJob`**; always completed exactly once (success on `HigherTermObserved` step-down, else failure) |
| `transferTimeoutJob` (:296) | `var Job?` | `onTransferLeadership` arm (:1658), clear sites | — | fires `EngineCommand.TransferTimeout` after `electionTimeoutMax`; cancelled on any resolution |

### ReadIndex (§6.4) — candidate **ReadIndexTracker** (PR-5, pending approval)

| Field (line) | Type | Written by | Read by | Invariant |
|---|---|---|---|---|
| `pendingReads` (:244) | `MutableList<PendingRead>` | `onRequestReadIndex` (:836), `resolveReadsIfQuorumFresh` (:1184), `becomeLeader` clear (:700), `failPendingReads` (:435) | resolution filter (:1177) | leader-only; every deferred completed (resolve, relinquish, or teardown) |
| `lastAckRound` (:255) | `MutableMap<NodeId, Long>` | AE/IS response handlers (:1121, :943), `becomeLeader` clear (:703) | freshness filter (:1179) | value = `echoedRound` the follower actually answered (round-slip fix) |
| `heartbeatRound` (:258) | `var Long` | `onHeartbeat` bump (:844), `becomeLeader` reset (:702) | stamped into AE (:885) and IS (:933) sends; `sinceRound` capture (:836) | per-leadership monotonic; followers echo it back |
| `currentTermNoOpIndex` (:267) | `var Long` | `appendNoOp` (:718) | §8 leader-completeness gate (:822, :1239) | reads never resolve before commitIndex reaches it |
| `pendingNoOpGate` (:274) | `MutableList<() -> Unit>` | `onRequestReadIndex` park (:823), drained in `advanceCommit` (:1239-1243), cleared on relinquish/becomeLeader | — | drained exactly when the no-op commits |

### Client dedup + forwarding (§8) — candidate **ProposalForwarder** (PR-6, pending approval)

| Field (line) | Type | Written by | Read by | Invariant |
|---|---|---|---|---|
| `myClientId` (:72) | `var ClientId` | ctor, re-mint in `detectCollision` (:1264) | `onLocalPropose` stamp (:1343) | auto ids re-mint on collision; durable ids throw `ClientIdCollisionException` |
| `serial` (:78) | `var Long` | `onLocalPropose` `++` (:1341), reset on re-mint (:1265) | — | monotonic per client id |
| `collisions` (:84) | `var CollisionDetector` | replaced on re-mint (:1266); `issued` (:1342) | `detectCollision` (:1256) | tracks serials THIS node issued under `myClientId` |
| `dedupCache` (:81) | `val LeaderDedupCache` (stateful) | record in `advanceCommit` (:1227), clear on relinquish (:758) | lookup in `onPropose` (:1374) | leader-scoped, best-effort, cold after step-down |
| `forwardedProposals` (:321) | `MutableMap<Long, PendingForward>` | `onPropose` non-leader branch (:1354), `onForwardResponse` remove (:1812), `flushWaitingForLeader`, teardown (:442) | — | **declared before `init` — teardown dereferences it (#1077)**; every deferred completed |
| `waitingForLeader` (:324) | `MutableList<Long>` | `onPropose` (:1359), drained `flushWaitingForLeader` (:1826-1849) | — | same pre-`init` constraint; flushed after every non-Close command (:391) |
| `nextForwardId` (:1773) | `var Long` | `onPropose` `++` (:1353) | — | monotonic correlation nonce (not teardown-touched, hence legally mid-file today) |

### Timers, misc

| Field (line) | Type | Notes |
|---|---|---|
| `electionJob` (:230), `heartbeatJob` (:231), `quorumCheckJob` (:232), `leaderLeaseJob` (:279) | `var Job?` | children of `scope`; only ever `cmd.trySend` back into the actor; cancelled/re-armed by role transitions |
| `recentVoterContacts` (:236) | `MutableSet<NodeId>` | CheckQuorum window: added by both response handlers (:1120, :942), drained by `onQuorumCheck` (:791), cleared in `becomeLeader` (:690) |
| `pending` (:211) | `MutableList<Pair<Long, CompletableDeferred<LogEntry>>>` | in-flight local proposals; completed in `advanceCommit` (:1231-1233), failed on relinquish/teardown |
| `pendingConfigChange` (:218) | `var CompletableDeferred<ClusterConfig>?` | one-change-at-a-time rule (:1536); completed in `onConfigCommitted` (:1603) |
| `proposeStartTimes` (:301) | `MutableMap<Long, ValueTimeMark>` | metrics only; removed in `emitProposeCommittedAndApplied` (:1280) |
| `traceClock` (:146) | `var Long` | monotonic trace ordering via `nextClock()` (:501) |

## PR-1 — `RaftState` holder (the enabling step)

**File:** new `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftState.kt`
(internal class — no public API change; `explicitApi()` unaffected).

**Pure consolidation, NO behavior change.** Every later seam needs to read/write the shared
consensus core without a back-reference to the whole engine; the holder is that core and
nothing else.

**Fields that move into `RaftState`:** exactly the "Log / commit / membership" table above —
`currentTerm`, `votedFor`, `log`, `currentCommitIndex`, `snapshotIndex`, `snapshotTerm`,
`snapshotConfig`, `membershipState`, `nextIndex`, `matchIndex`. The pure derived helpers move
with their data: `entryAt` (:484), `lastLogIndex` (:486), `lastLogTerm` (:488), `termAt`
(:491) — they are functions of moved fields only (they already delegate to the extracted
`RaftLogMath.kt` free functions, the established precedent for this kind of carve).

**What stays in `RaftEngine`:** everything else — all flows, the `cmd` channel, all
concern-specific pending/deferred/timer/metric/dedup/forwarding fields (each leaves in its
OWN later PR, not via the holder), `recomputeMembership` (it emits trace events and updates
`_membership`/`_role` — engine orchestration that *writes* `state.membershipState`), and the
persistence choke-points `persistTermAndVote`/`persistVote` (they call `storage` then write
`state.currentTerm`/`state.votedFor` — the persisted-before-in-memory ordering must stay
visible at one choke-point).

**Deliberately NOT a god-holder:** consolidating all ~35 fields would immediately be undone
by PR-2..4 pulling concern-owned fields back out of it. The holder is only the state ≥ 2
concerns share. (Open question 1 flags this divergence from the issue stub's literal wording.)

**Mechanics:**
- `private val state = RaftState(bootstrapConfig)` declared **before the `init` block**
  (#1077 rule — and this becomes the standing rule for every machine field in PR-2..6).
- Call sites rewritten `currentTerm` → `state.currentTerm` etc. Direct rewrites, not
  `by state::x` property delegation — greppable, honest, and later machines will take
  `state` directly. The diff is large but 100% mechanical; the reviewer's job is "no
  expression changed shape".
- `RaftState`'s KDoc carries the locking model: *actor-confined — all access from inside the
  engine's actor loop (or the init-restore coroutine that strictly precedes it); confinement
  is provided by the single dedicated actor coroutine draining `cmd`, which the repo
  thread-safety rule sanctions as a real primitive. Never hand `RaftState` to a coroutine
  that isn't an actor message handler; cross-thread reads go through RaftEngine's StateFlows.*
  This preserves (not weakens) the existing discipline — no locks are added because no new
  concurrency is introduced.

**Verified by:** the entire `:kuilt-raft` suite unchanged (zero test edits is itself the
review signal). Full gate: `timeout 600 ./gradlew :kuilt-raft:build detektAll --rerun-tasks`.

## Candidate seams, in extraction order

Shape rule for all machines (Open question 3 confirms it): machines are **synchronous,
decision-returning objects** — they own their state and offset/quorum arithmetic and return a
sealed decision; the engine keeps all `send(...)`/`emitTrace(...)`/storage-mutation
side-effects at the call site. This keeps each machine unit-testable as a plain class
(single-class unit tests are fine and are NOT hand-rolled clusters) while the sim suite pins
the integrated behavior. Machines never launch coroutines except a timer that only
`cmd.trySend`s — asynchrony is always expressed as an `EngineCommand` re-entering the actor.

### PR-2 — `SnapshotSender` (leader-side chunked InstallSnapshot, §7)

**New type:** `internal class SnapshotSender(private val storage: RaftStorage, private val chunkBytes: () -> Int)`
in `internal/SnapshotSender.kt`.

**Moves:** `SnapshotXfer` (:221), `snapshotXfer` (:222), `chunkBytes()` (:896-900),
`HEADER_BUDGET` (:1888-1891), the load/slice/advance logic of `sendSnapshotChunk`
(:906-936) and the xfer-advance half of `onInstallSnapshotResponse` (:945-957).

**Boundary:**
```
suspend fun nextChunk(peer: NodeId, restart: Boolean): Chunk?        // null: nothing stored yet
    class Chunk(val meta: SnapshotMeta, val offset: Long, val data: ByteArray, val done: Boolean)
fun onAck(peer: NodeId, nextOffset: Long): AckOutcome
    sealed: Complete(lastIncludedIndex) | SendNext | NoTransfer
fun abandonAll()                                                      // relinquishToFollower :757
```

**Engine after extraction:**
- `sendAppendEntries` divert (`:851-854`): `snapshotSender.nextChunk(peer, restart = true)?.let { send(peer, RaftMessage.InstallSnapshot(currentTerm, …, heartbeatRound)) }` — term/round stamping and the `emitTrace`/`debug` stay in the engine.
- `onInstallSnapshotResponse`: term guard, `recentVoterContacts`, `lastAckRound`, and
  `resolveReadsIfQuorumFresh` stay (:940-944); then `when (snapshotSender.onAck(from, m.nextOffset))` — `Complete` → update `state.matchIndex`/`state.nextIndex` (preserving the `maxOf` at :948), resume `sendAppendEntries(from)`, `tryAdvanceLeaderCommit()`; `SendNext` → `nextChunk(from, restart = false)` + send; `NoTransfer` → return.
- `relinquishToFollower` calls `snapshotSender.abandonAll()`.

**Verified by (sim, all existing):** `InstallSnapshotTest.offlineFollower_rejoinsViaInstallSnapshot_afterCompaction`,
`InstallSnapshotTest.chunkedTransfer_reassemblesUnderTinyMaxPayload` (drives multi-chunk via
`RaftSimulation(maxPayloadBytes = tiny)`), `MembershipTest.installSnapshot_adoptsConfigCompactedAwayFromTheLog`,
`SnapshotJointConfigTest.snapshotMidJoint_installerResumesJointPhase`,
`SnapshotRecoveryTest.nodeRestart_recoversSnapshotBaseline_andReplaysInstallThenTail`,
plus `CompactionTest` for the floor interplay.

### PR-3 — `SnapshotReceiver` (follower-side reassembly, §7)

**New type:** `internal class SnapshotReceiver` in `internal/SnapshotReceiver.kt`.

**Moves:** `SnapshotReassembly` (:225), `incomingSnapshot` (:226), and the chunk-accept
decision block of `onInstallSnapshot` (:970-988): create-at-offset-0, meta/offset mismatch →
re-advertise, accept-and-buffer, done → hand back the assembled bytes.

**Boundary:**
```
fun onChunk(meta: SnapshotMeta, offset: Long, data: ByteArray, done: Boolean): ChunkOutcome
    sealed: ReAdvertise(haveOffset) | AwaitMore(haveOffset) | Complete(meta, bytes)
fun reset()                                                           // after install :1017
```

**Engine after extraction:** `onInstallSnapshot` keeps the term guards, role/lease/timer
bookkeeping (:962-968), maps `ChunkOutcome` to the three existing
`InstallSnapshotResponse(currentTerm, have, echoedRound = m.round)` sends, and on `Complete`
calls the **unchanged** `finalizeInstalledSnapshot` (:991-1021) — storage, log reset,
commit-index seed, `snapshotConfig` adoption, `recomputeMembership`, and the
`Committed.Install` emit are deeply coupled engine/`RaftState` mutations and deliberately do
NOT move.

**Verified by:** same suite as PR-2 (`chunkedTransfer_reassemblesUnderTinyMaxPayload` is the
reassembly workhorse; the partition/heal in `offlineFollower_rejoinsViaInstallSnapshot_afterCompaction`
exercises restart-at-offset-0). Bonus: `SnapshotReceiver` is a pure decision machine —
a narrow single-class unit test of the out-of-order/re-advertise ladder is welcome.

### PR-4 — `LeadershipTransferMachine` (§3.10)

**New type:** `internal class LeadershipTransferMachine(private val scope: CoroutineScope, private val raftConfig: RaftConfig, private val signalTimeout: () -> Unit)`
in `internal/LeadershipTransferMachine.kt`, where `signalTimeout = { cmd.trySend(EngineCommand.TransferTimeout) }`.
Field declared **before `init`** — `failPendingTransfer` runs in actor teardown (:399).

**Moves:** `transferTarget` (:287), `transferDeferred` (:293), `transferTimeoutJob` (:296),
`failPendingTransfer` (:452-458), `completePendingTransfer` (:461-467), the accept/arm body
of `onTransferLeadership` (:1647-1661), `onTransferTimeout` (:1706-1713), `onCancelTransfer`
(:1718-1725), and the caught-up predicate of `sendTransferSync`/`sendTimeoutNowIfReady`
(:1674-1700).

**Boundary:**
```
val inFlightTarget: NodeId?                                  // propose gate + vote-stickiness query
fun start(target: NodeId, response: CompletableDeferred<Unit>): Boolean   // false = already in flight
fun onPeerAck(from: NodeId, matchIdx: Long, commitIdx: Long): Boolean     // true = send TimeoutNow now
fun onTimeout(): NodeId?      // target to trace-abandon, or null (stale timer)
fun onCancel(): NodeId?       // ditto
fun onLeadershipRelinquished(reason: StepDownReason)   // complete iff HigherTermObserved, else fail
fun reset()                                            // becomeLeader :706-709
fun fail(cause: LeadershipTransferException)           // actor teardown :399
```

**Engine after extraction:**
- `onTransferLeadership` keeps the four validation rejects (:1633-1650: not-leader, self,
  non-voter, already-in-flight via `start(...) == false`) plus the trace emit, then calls
  `sendAppendEntries(target)` and `if (transfer.onPeerAck(target, state.matchIndex[target] ?: 0L, state.currentCommitIndex)) sendTimeoutNow(target)`.
- `onAppendEntriesResponse` success path (:1131) → same `onPeerAck` call replacing
  `sendTimeoutNowIfReady`.
- `onPropose` gate (:1366-1370) and `onRequestVote` stickiness exception (:604) query
  `transfer.inFlightTarget`.
- `relinquishToFollower` (:762-768) → `transfer.onLeadershipRelinquished(reason)`.
- `sendTimeoutNow` (:1685) stays — it is a raw send.
- **`onTimeoutNow` (:1738-1768) stays in the engine**: it is the *receiver* side — an
  election trigger on a follower, coupled to term/role/`startRealElection`, not to the
  leader's transfer state.

**Invariant carried into the type:** the all-or-none nullity of
target/deferred/timeout-job becomes structural — one private
`data class InFlight(target, deferred, timeoutJob)` nullable field instead of three vars.
(This is a representation change inside the machine, not a behavior change.)

**Verified by:** `LeadershipTransferTest` in full —
`transferLeadership_happyPath_targetBecomesLeader`,
`proposalsDuringTransfer_rejectedWithNotLeaderException`,
`transferLeadership_targetUnreachable_autoTimeoutResumesLeader`,
`cancelTransfer_abortsInFlightTransfer`, `transferLeadership_noCommittedEntryLoss`,
`timeoutNow_fromNonLeader_isIgnored` (uses `RaftSimulation.deliverTimeoutNow`),
`transferLeadership_abandonedEmitsTraceEvent`, plus the three immediate-reject tests.
`CheckQuorumTest` guards the relinquish interplay.

### PR-5 (approved) — `ReadIndexTracker` (§6.4 / §8 gate)

**Moves:** `pendingReads` + `PendingRead` (:241-244), `lastAckRound` (:255),
`heartbeatRound` (:258), `currentTermNoOpIndex` (:267), `pendingNoOpGate` (:274),
`onRequestReadIndex` (:816-838), `resolveReadsIfQuorumFresh` (:1171-1189),
`failPendingReads` (:435-439), the no-op-gate drain of `advanceCommit` (:1239-1243).
Boundary sketch: `val round: Long` / `fun bumpRound()` (engine stamps sends from it, :885/:933),
`fun recordAck(from, echoedRound)`, `fun request(deferred, commitIdx, membership): ReadDecision`,
`fun resolve(membership, selfId): List<PendingRead>`, `fun onNoOpCommitted()`, `fun reset()`,
`fun failAll(cause)`. This machine encodes the two BLOCKER fixes (round-slip nonce,
joint dual-majority) — its KDoc must carry those over verbatim.
**Verified by:** `ReadIndexTest` in full, especially `roundSlipAckDoesNotConfirmReadIndex`,
`staleAckDoesNotConfirmReadIndex`, `shrinkingJointFastPathDoesNotConfirmReadWithoutOldMajority`,
`jointConsensusReadRequiresBothOldAndNewMajority`, `freshLeaderReadIndexWaitsForCurrentTermNoOpToCommit`.

### PR-6 (approved) — `ProposalForwarder` (§8)

**Moves:** `forwardedProposals` + `PendingForward` (:321, :1776-1780), `waitingForLeader`
(:324), `nextForwardId` (:1773), `onForwardResponse` (:1811-1819), the queue/flush logic of
the non-leader `onPropose` branch (:1347-1361) and `flushWaitingForLeader` (:1826-1849),
`failForwardedProposals` (:442-446). The machine field MUST be declared before `init`
(#1077 — this state is exactly what that bug was about). `onForward` (:1783-1808, the
*leader* side) stays — it is a propose-path entry point, not forwarder state.
**Verified by:** `RaftProposeForwardingTest` in full (8 tests, incl.
`cancelledForwardingPropose_doesNotCommitLater`,
`followerPropose_cancelledWhileWaitingForLeader_doesNotHang`),
`ForwardDedupThreadingTest`, `RaftEngineDedupIntegrationTest`.

### Explicitly NOT extracted (end state)

Election/pre-vote, AppendEntries handling, `advanceCommit`, and the §6 membership pipeline
(`recomputeMembership`/`onChangeMembership`/`onConfigCommitted`) stay in `RaftEngine` — they
ARE the consensus core and are coupled through term/role/log by design. After PR-6 the
residual engine is ~1,000-1,100 lines orchestrating five small machines over one `RaftState`.
That is the intended stopping point; further slicing would trade real coupling for
ceremony.

## Ordered PR sequence

| # | PR (one behavior-move each) | Moves | Gate tests (existing, sim-driven) |
|---|---|---|---|
| 1 | `refactor(raft): consolidate core consensus state into RaftState` | 10 shared-core fields + 4 log helpers | whole `:kuilt-raft` suite, zero test edits |
| 2 | `refactor(raft): extract leader-side SnapshotSender` | `snapshotXfer` machine | `InstallSnapshotTest`, `SnapshotJointConfigTest`, `SnapshotRecoveryTest`, `CompactionTest`, `MembershipTest.installSnapshot_*` |
| 3 | `refactor(raft): extract follower-side SnapshotReceiver` | `incomingSnapshot` machine | same as PR-2 |
| 4 | `refactor(raft): extract LeadershipTransferMachine` | transfer target/deferred/timer | `LeadershipTransferTest` (all), `CheckQuorumTest` |
| 5 | `refactor(raft): extract ReadIndexTracker` | reads/rounds/no-op gate | `ReadIndexTest` (all) |
| 6 | `refactor(raft): extract ProposalForwarder` | forwarding maps/nonce | `RaftProposeForwardingTest`, `ForwardDedupThreadingTest`, `RaftEngineDedupIntegrationTest` |

Strictly sequential; each PR merges (auto-merge on green after opus review) before the next
worker is dispatched. After the sequence, run one whole-branch opus review of the final
`internal/` package shape.

**Per-PR checklist (goes in every dispatch brief):**
1. `git branch --show-current` + `pwd` defensive check; ABORT if not under `.claude/worktrees/`.
2. `git fetch origin main` and re-verify every cited line number against `origin/main` —
   this file drifts weekly.
3. The move, mechanical, preserving the landed fixes table above.
4. `timeout 600 ./gradlew :kuilt-raft:build detektAll --rerun-tasks` (foreground; tasks
   genuinely `EXECUTED`, not `FROM-CACHE`).
5. **Rebase onto `origin/main` immediately before opening the PR** (HOT file), open ready
   (not draft — draft→ready wedges `ci-required`, see #1132), `gh pr merge --auto --squash`
   only after the opus review.

## Risk / stop-and-replan

- **Any sim test flip = STOP.** Do not widen `raftRunTest` timeouts, do not retry-until-green,
  do not touch `MAX_ELECTION_THRASH`. A hang or `AssertionError` from an `await*` helper
  arrives with `RaftSimulation.dumpState()` output (per-node role/term/commitIndex/log-range +
  event histogram + worst-off node's last 12 trace events) — capture it verbatim and hand the
  failing test + dump back to the dispatcher for a strong-model diagnosis. Repo CLAUDE.md:
  "A hang/timeout is a STOP-and-re-plan signal."
- **`UncompletedCoroutinesError` with no dump** = an ungated hot loop froze virtual time —
  a real bug introduced by the move (most likely a machine that re-sends without awaiting an
  ack, recreating the install→reject→install spin the `:1036-1045` comment documents). Same
  STOP rule.
- **One test at a time when debugging:** `timeout 90 ./gradlew :kuilt-raft:jvmTest --tests "<oneTest>"`,
  OS-fenced. Never loop the whole suite hunting a flake.
- **Ordering hazards a reviewer must check per PR:** (a) machine fields declared before
  `init` when teardown touches them (#1077); (b) persisted-before-in-memory ordering in
  `persistTermAndVote` untouched; (c) clear-on-`becomeLeader` and clear-on-`relinquishToFollower`
  call sites all still fire — a missed `reset()`/`abandonAll()` is silent state leakage across
  terms, exactly the class of bug the sim suite may only catch probabilistically.
- **Merge-train hygiene:** poll `mergeStateStatus`, key check verdicts to the latest run id;
  full `./gradlew build` locally before enabling auto-merge (jvmTest alone misses
  Android/Native variant compiles).

## Resolved decisions (2026-07-06, Iain)

1. **Holder scope → shared core only.** `RaftState` is the 10 shared-core fields + log
   helpers, NOT the literal ~30-field consolidation. Concern-owned fields go directly into
   their machines in PR-2..6 (no move-twice).
2. **Extraction depth → full.** All six PRs land in this pass, including PR-5
   (`ReadIndexTracker`) and PR-6 (`ProposalForwarder`).
3. **Machine shape → decision-returning.** Machines own state + arithmetic and return sealed
   decisions; the engine keeps every send/trace/storage side-effect at the call site. Machines
   are pure, unit-testable plain classes.
4. **Snapshot split → two PRs.** Leader-side `SnapshotSender` (PR-2) and follower-side
   `SnapshotReceiver` (PR-3) extract separately.

### Still open (defer to PR-5)

5. **`heartbeatRound` placement.** Move into `ReadIndexTracker` (it exists for read freshness;
   engine stamps sends via `tracker.round`) vs. leave in `RaftState` as shared replication
   state. Decide when PR-5 is dispatched — does not gate PR-1..4.
