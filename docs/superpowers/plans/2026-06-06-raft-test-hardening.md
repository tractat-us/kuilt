# Bulletproofing kuilt-raft consensus tests against silent hangs

> **Status:** plan / for review. Discovered while implementing #114 (log compaction); the failure class is shared with #172.

## The failure class (what bit us)

A coroutine consensus test under `runTest(UnconfinedTestDispatcher())` **hangs forever instead of failing** when a node never reaches an expected state. Two compounding causes:

1. **Unbounded cluster-state awaits.** Tests wait with `node.commitIndex.first { it >= N }`. If the cluster never converges (a real bug), this suspends forever.
2. **A background `while(true){ delay }` heartbeat keeps virtual time advancing**, so `runTest` never goes idle and can't end; the only backstop is the *default 60 s wall-clock* timeout — which then surfaces as an opaque `UncompletedCoroutinesError`/timeout **with zero state**, forcing a `jstack` to even learn *which* test hung.

Concretely (#114): `offlineFollower_rejoinsViaInstallSnapshot` hung for >60 s; `chunkedTransfer` "fast-failed" only because it happened to assert before awaiting. The real root cause (term inflation on a partitioned voter) was invisible until a hand-written trace dump + a jstack.

**Goal:** make this class (1) *architecturally impossible* — a non-converging cluster test fails in **seconds** with a **full state dump**, never hangs — and (2) *trivially debuggable* — every failure already carries the evidence we'd otherwise reconstruct by hand.

---

## Part 1 — Architecturally impossible to hang

### 1.1 Mandatory tight timeouts (no bare `runTest`)
- Add `raftRunTest(timeout: Duration = 5.seconds, body)` to `RaftTestFixtures` wrapping `runTest(UnconfinedTestDispatcher(testScheduler), timeout = timeout)`. Default **5 s**, overridable per-test but never unbounded.
- Migrate every `:kuilt-raft` test off bare `runTest(UnconfinedTestDispatcher())` to `raftRunTest { }`.
- **CI guard (grep):** fail the build if `runTest(` appears in `kuilt-raft/src/**Test**` without an explicit `timeout =` (or outside `raftRunTest`). A seconds-scale test that's allowed to run a minute is a defect.

### 1.2 Ban unbounded cluster-state awaits — funnel through the harness
All "wait for the cluster to reach X" goes through `RaftSimulation` helpers that are **bounded against the test scheduler** and **dump-on-timeout**:
- `suspend fun awaitCommit(index: Long, on: Collection<NodeId> = nodeIds, within: Duration = 2.seconds)`
- `suspend fun awaitLeader(within: Duration = 2.seconds): RaftNode`
- `suspend fun awaitInstalled(id: NodeId, within: Duration = 2.seconds): Committed.Install`
- `suspend fun awaitRole(id, role, within)`

Each wraps the underlying `flow.first { … }` in `withTimeout(within)`; on `TimeoutCancellationException` it throws `AssertionError(sim.dumpState("awaitCommit($index) on $on timed out"))`. Direct `node.commitIndex.first { … }` / `role.first { … }` in test bodies is **banned by a CI grep guard** — the helper is the only sanctioned path.

### 1.3 Fix the harness coroutine-scope bug (latent hang source)
`RaftSimulation`'s new `init` launches the `AppliedStateMachine` collectors — and `collectInstalls` launches its collector — on `scope` (the **test** scope) rather than `nodeScope` (`backgroundScope`). Hot `committed.collect` never completes, so on a *passing* test these would trip `UncompletedCoroutinesError` at teardown (another opaque hang/late-failure). **Move all internal collectors to `nodeScope`.**

---

## Part 2 — Diagnostics that make debugging trivial

### 2.1 Always-on trace capture in `RaftSimulation`
From construction, collect every node's `trace` into a bounded per-node ring buffer (last ~256 events) on `nodeScope`. Zero test boilerplate; available to every failure dump.

### 2.2 `sim.dumpState(reason): String` — the productized version of the throwaway diagnostic
On any failed await (and callable directly), emit a single structured block:
```
=== CLUSTER STATE: <reason> ===
v1: LEADER   term=4 commit=21 snapshot=16/3 log=[17..21] | timeouts=0 leader↑=1 follower↓=0 install↑=1 installAccepted=0
v2: FOLLOWER term=4 commit=21 snapshot=0      log=[1..21] | …
v3: CANDIDATE term=37 commit=0  snapshot=0    log=[]       | timeouts=36 leader↑=0 …   ← term inflation / churn obvious
--- v3 recent trace (last 12) --- … Timeout(term=35) … RequestVote … VoteDenied(LogNotUpToDate) …
```
The counts surface **leadership thrash** (`leader↑` across nodes) and **term inflation** (a candidate term far above the leader's) at a glance — the two signals that would have made #114 a 2-minute diagnosis instead of a jstack expedition. (`nextIndex`/`matchIndex` are engine-internal; expose a test-only inspector or infer from trace — decide during impl.)

### 2.3 Convert the throwaway `DiagnoseInstallSnapshot.kt` into this
Delete the scratch diagnostic; its content becomes `dumpState` + the convergence helpers. Nothing bespoke survives — the infra carries it.

---

## Part 3 — The actual #114 test fix (separate from the infra)

The `InstallSnapshot` *implementation* is sound; the test models "offline" wrong. `partitionOff` keeps the node **running** (election timer firing → term inflation → disruptive rejoin). The issue's scenario is a node **genuinely offline** (crashed/stopped, *not* inflating term).

**Fix:** model offline as **crash + restart**, which is also higher-fidelity:
- Add `RaftSimulation.crash(id)` (cancel the node's child scope; its election timer stops, term frozen in persisted `InMemoryRaftStorage`) and `restart(id)` (recreate the node from its persisted storage).
- The rejoin test: elect leader, `crash(c)`, propose 20, compact past c's last index, `restart(c)`, `awaitCommit(20, on=[c])`, assert exactly one `Committed.Install` and state-machine convergence.
- A restarted-from-empty node starts at term 0 ≪ leader term → accepts the `InstallSnapshot` → the path under test actually runs.

(Alternative if crash/restart is too much harness surgery now: make the offline node a **learner** — non-voting, no election timeouts, still receives `InstallSnapshot`. Lower fidelity but unblocks #114 immediately. Recommend crash/restart; fall back to learner only if needed.)

---

## Part 4 — File the real production gap (out of #114 scope)

A partitioned **voter** genuinely *will* inflate its term and disrupt the cluster on rejoin — kuilt-raft has no **PreVote / §4.2.3 disruptive-server** protection. File:
> `feat(kuilt-raft): PreVote (or §4.2.3 leader-stickiness) to stop a rejoining partitioned voter from disrupting the cluster` — label `needs-design`. Reference this plan. Not required for #114, but real.

---

## Work items (propose as issues under a small "test-hardening" umbrella)

| # | Item | Part | Label |
|---|------|------|-------|
| 1 | `raftRunTest` + 5 s default + CI grep guard for bare `runTest` | 1.1 | ready |
| 2 | `awaitCommit/awaitLeader/awaitInstalled` bounded+dumping helpers; ban direct awaits via grep guard | 1.2 | ready |
| 3 | Move `RaftSimulation` internal collectors to `nodeScope` | 1.3 | ready |
| 4 | Always-on trace ring buffer + `sim.dumpState()` | 2.1–2.2 | ready |
| 5 | `crash()`/`restart()` sim support + rewrite `InstallSnapshotTest` to use it | 3 | ready (part of #114) |
| 6 | PreVote / disruptive-server protection | 4 | needs-design |

Items 1–4 are the reusable infra (apply the same shape to #172's module). Item 5 unblocks #114. Item 6 is the deferred production hardening.

## Sequencing
Land **1–4 first** (the infra) so that when #114's `InstallSnapshotTest` is rewritten (item 5) it already fails fast with a dump on any regression. Items 1–4 are independent of the raft engine changes and can land on `main` ahead of, or alongside, the #114 stack.
