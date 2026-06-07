# kuilt-raft PreVote + §4.2.3 Leader-Stickiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a partitioned voter from inflating its term and disrupting a healthy leader on rejoin, via a PreVote round plus the §4.2.3 leader-stickiness guard.

**Architecture:** A candidate runs a non-mutating *pre-vote* probe (new `PreVote`/`PreVoteResponse` messages) before the real election; it only bumps its term once a quorum of peers — none of whom currently hear a leader — grant the probe. A per-node leader-lease (`delay(electionTimeoutMin)` Job arming a `leaderAlive` flag) tells a peer whether a leader is alive; the same flag gates a new guard in `onRequestVote` that rejects a higher-term vote request without adopting its term while a leader is alive.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (actor over `Channel`, `Job`-based timers), kotlinx.serialization (CBOR), kotlin.test + `UnconfinedTestDispatcher`.

**Spec:** `docs/superpowers/specs/2026-06-06-raft-prevote-design.md`

**Sequencing:** Land **after #114** (shares `RaftEngine`/`RaftMessage`) and **after #192** (uses `raftRunTest` + bounded `awaitX`/`dumpState` test helpers). If #192 has not landed, substitute `runTest(UnconfinedTestDispatcher(), timeout = 5.seconds)` for `raftRunTest` and inline a per-test timeout.

---

## File structure

| File | Change | Task |
|------|--------|------|
| `internal/RaftMessage.kt` | + `PreVote`, `PreVoteResponse` | 1 |
| `RaftTraceEvent.kt` | + `PreVoteStarted`/`PreVoteGranted`/`PreVoteDenied`; `DenyReason.LeaderAlive` | 1,3 |
| `internal/RaftEngine.kt` | leader-lease + `onPreVote` (T1); probe rewrite of `onElectionTimeout` + `onPreVoteResponse` + `startRealElection` (T2); `onRequestVote` stickiness guard (T3) | 1–3 |
| `kuilt-raft/src/commonTest/.../raft/PreVoteTest.kt` (new) | granter + candidate + stickiness + canonical rejoin tests | 1–3 |
| existing PBT/chaos suite | term-stability-under-partition invariant | 4 |

`currentTerm` is private; **all term assertions go through trace** (`RaftTraceEvent.Timeout.newTerm`, `BecomeLeader.term`, `PreVote*`). Collect a node's `trace` on `backgroundScope` before the scenario, then assert on the captured list.

**Environment:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`. Inner loop: `./gradlew :kuilt-raft:jvmTest`.

---

## Task 1: PreVote messages, leader-lease, and the granter (`onPreVote`)

Pure additive: a node can now answer a pre-vote, and it tracks whether a leader is alive. No candidate behavior yet (`onElectionTimeout` unchanged this task).

**Files:** Modify `internal/RaftMessage.kt`, `RaftTraceEvent.kt`, `internal/RaftEngine.kt`. Test `PreVoteTest.kt` (new).

- [ ] **Step 1: Write the failing test**

Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/PreVoteTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PreVoteTest {

    // A follower that has NOT heard from a leader grants a pre-vote for an up-to-date candidate.
    @Test
    fun granterGrantsPreVoteWhenNoLeaderAndLogOk() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val v1 = NodeId("v1"); val v2 = NodeId("v2")
        val granterTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(v2).trace.collect { granterTrace += it } }
        // isolate everyone so no leader is ever established → leaderAlive stays false on v2
        sim.partition(setOf(v1), setOf(v2), setOf(NodeId("v3")))
        // hand v2 a PreVote from v1 (empty logs are mutually up-to-date)
        sim.deliverPreVote(to = v2, from = v1, term = 1L, lastLogIndex = 0L, lastLogTerm = 0L)
        sim.nodes // settle
        assertTrue(granterTrace.any { it is RaftTraceEvent.PreVoteGranted },
            "no-leader granter should grant: $granterTrace")
    }

    // After hearing AppendEntries from a leader, the same node DENIES pre-votes (leaderAlive).
    @Test
    fun granterDeniesPreVoteWhileLeaderAlive() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.nodeIds.first { it != leaderId }
        sim.nodes.getValue(follower).commitIndex.first { it >= 1L } // ensure it heard the leader
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(follower).trace.collect { trace += it } }
        sim.deliverPreVote(to = follower, from = leaderId, term = 99L, lastLogIndex = 99L, lastLogTerm = 99L)
        sim.settle()
        assertTrue(trace.any { it is RaftTraceEvent.PreVoteDenied },
            "follower hearing the leader must deny pre-votes: $trace")
    }
}
```

This needs two tiny sim affordances — `deliverPreVote(...)` (encode + inject a `PreVote` to a node via the network) and `settle()` (let the actor drain). Add them to `RaftSimulation.kt`:

```kotlin
suspend fun deliverPreVote(to: NodeId, from: NodeId, term: Long, lastLogIndex: Long, lastLogTerm: Long) {
    val bytes = kotlinx.serialization.cbor.Cbor.encodeToByteArray<RaftMessage>(
        RaftMessage.PreVote(term, from, lastLogIndex, lastLogTerm)
    )
    network.deliver(from = from, to = to, bytes = bytes)   // reuse the network's existing inject path
}
suspend fun settle() { repeat(5) { kotlinx.coroutines.yield() } }
```
If `InMemoryRaftNetwork` has no public `deliver`, add a thin one that pushes a `RaftEnvelope(from, bytes)` into the `to` node's channel (mirror how `sendTo` enqueues). `RaftMessage` is `internal`, so `PreVoteTest`/`RaftSimulation` are in the same module — fine.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*PreVoteTest*"`
Expected: FAIL — `RaftMessage.PreVote`, `RaftTraceEvent.PreVoteGranted/Denied`, `deliverPreVote` unresolved.

- [ ] **Step 3: Add the wire messages**

In `internal/RaftMessage.kt`, add to the sealed interface:

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
        val term: Long,            // granter's REAL currentTerm
        val voteGranted: Boolean,
        val proposedTerm: Long,    // echoes PreVote.term so the candidate matches the round
    ) : RaftMessage
```

- [ ] **Step 4: Add trace events + DenyReason**

In `RaftTraceEvent.kt`:
```kotlin
    public data class PreVoteStarted(override val clock: Long, val node: NodeId, val proposedTerm: Long) : RaftTraceEvent
    public data class PreVoteGranted(override val clock: Long, val node: NodeId, val to: NodeId, val proposedTerm: Long) : RaftTraceEvent
    public data class PreVoteDenied(override val clock: Long, val node: NodeId, val to: NodeId, val proposedTerm: Long, val reason: DenyReason) : RaftTraceEvent
```
Add `LeaderAlive` to the `DenyReason` enum (find its declaration; append the entry).

- [ ] **Step 5: Add leader-lease state + `onPreVote`, wire the dispatcher**

In `RaftEngine.kt`, add actor state near `votedFor`:
```kotlin
    private var leaderAlive = false
    private var leaderLeaseJob: Job? = null
```
Add the lease helper (place near `resetElectionTimeout`):
```kotlin
    private fun armLeaderLease() {
        leaderAlive = true
        leaderLeaseJob?.cancel()
        leaderLeaseJob = scope.launch {
            delay(raftConfig.electionTimeoutMin.inWholeMilliseconds)
            leaderAlive = false
        }
    }
```
Arm it on valid leader contact. In `onAppendEntries`, right after the point where it sets `_leader.value = m.leaderId` and `resetElectionTimeout()`, add `armLeaderLease()`. Do the same in `onInstallSnapshot` (after it sets `_leader.value = m.leaderId`). In `becomeLeader`, add `leaderAlive = true; leaderLeaseJob?.cancel()`. In `stepDown`, add `leaderAlive = false; leaderLeaseJob?.cancel()`.

Add `onPreVote` (pure — touches no term/vote/timer):
```kotlin
    private suspend fun onPreVote(from: NodeId, m: RaftMessage.PreVote) {
        val logOk = isLogUpToDate(log.lastOrNull(), m.lastLogIndex, m.lastLogTerm)
        val grant = m.term > currentTerm && logOk && !leaderAlive
        if (grant) {
            emitTrace(RaftTraceEvent.PreVoteGranted(nextClock(), transport.selfId, from, m.term))
        } else {
            val reason = if (leaderAlive) DenyReason.LeaderAlive
                         else if (!logOk) DenyReason.LogNotUpToDate else DenyReason.StaleTerm
            emitTrace(RaftTraceEvent.PreVoteDenied(nextClock(), transport.selfId, from, m.term, reason))
        }
        send(from, RaftMessage.PreVoteResponse(currentTerm, grant, m.term))
    }
```
Add a no-op `onPreVoteResponse` stub for now (real in Task 2):
```kotlin
    private suspend fun onPreVoteResponse(from: NodeId, m: RaftMessage.PreVoteResponse) { /* Task 2 */ }
```
Extend the `onMessage` `when`:
```kotlin
        is RaftMessage.PreVote          -> onPreVote(from, m)
        is RaftMessage.PreVoteResponse  -> onPreVoteResponse(from, m)
```

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*PreVoteTest*"`
Expected: PASS (both Task 1 tests). Then full `./gradlew :kuilt-raft:jvmTest` — all prior tests green.

- [ ] **Step 7: Commit**

```bash
git add kuilt-raft/src
git commit -m "feat(kuilt-raft): PreVote messages + leader-lease + granter (#193)"
```

---

## Task 2: Candidate side — probe instead of bumping the term

Rewrite `onElectionTimeout` to run a pre-vote probe; only bump the term on a pre-vote quorum. This is where the disruptive-rejoin bug is actually fixed.

**Files:** Modify `internal/RaftEngine.kt`, `RaftTraceEvent.kt` (already added). Test `PreVoteTest.kt`.

- [ ] **Step 1: Write the failing tests**

Append to `PreVoteTest.kt`:

```kotlin
    // The headline fix: a partitioned voter does NOT inflate its term, so on heal it does not
    // depose the leader. Asserted via trace: the leader emits no BecomeFollower; the offline node
    // emits no real Timeout (term bump). And the offline node rejoins (commitIndex catches up).
    @Test
    fun partitionedVoterDoesNotDisruptLeaderOnRejoin() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }
        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        sim.partition(setOf(offline), sim.nodeIds.filter { it != offline }.toSet())
        repeat(3) { leader.propose(byteArrayOf(it.toByte())) }
        sim.nodes.getValue(leaderId).commitIndex.first { it >= 3L }
        sim.settle()                                   // give the partitioned node time to (not) inflate
        sim.heal()
        sim.nodes.getValue(offline).commitIndex.first { it >= 3L }   // it rejoins

        assertTrue(leaderTrace.none { it is RaftTraceEvent.BecomeFollower },
            "healthy leader must not be deposed by the partitioned node: $leaderTrace")
    }

    // A node only bumps its term once a pre-vote quorum is reached: with peers reachable and no
    // leader, an election still completes (sanity that pre-vote doesn't deadlock normal elections).
    @Test
    fun electionStillCompletesViaPreVote() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)        // election must succeed through the pre-vote path
        assertTrue(leader.role.value is RaftRole.Leader)
    }

    // Single-voter still elects instantly (self pre-vote satisfies quorum).
    @Test
    fun singleVoterElectsInstantly() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 1)
        val leader = awaitLeader(sim)
        assertTrue(leader.role.value is RaftRole.Leader)
    }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*PreVoteTest.partitionedVoterDoesNotDisruptLeaderOnRejoin*"`
Expected: FAIL — today `onElectionTimeout` inflates the term, the partitioned node disrupts on heal, the leader emits `BecomeFollower` (and/or the offline node never rejoins → would hang, caught by the 5 s timeout as a fast failure).

- [ ] **Step 3: Add candidate state + rewrite `onElectionTimeout`, add `onPreVoteResponse` + `startRealElection`**

In `RaftEngine.kt`, add state near `votesGranted`:
```kotlin
    private var preVoteTerm: Long? = null
    private val preVotesGranted = mutableSetOf<NodeId>()
```
Replace the body of `onElectionTimeout` with the probe (keep the old body verbatim inside the new `startRealElection`):
```kotlin
    private suspend fun onElectionTimeout() {
        if (_role.value is RaftRole.Leader) return
        _role.value = followerRole                  // a re-timing-out Candidate drops back during the probe
        preVoteTerm = currentTerm + 1
        preVotesGranted.clear(); preVotesGranted += transport.selfId
        resetElectionTimeout()
        if (preVotesGranted.size >= clusterConfig.quorumSize) { startRealElection(); return }
        emitTrace(RaftTraceEvent.PreVoteStarted(nextClock(), transport.selfId, preVoteTerm!!))
        val pv = RaftMessage.PreVote(preVoteTerm!!, transport.selfId, lastLogIndex, lastLogTerm)
        otherVoters.forEach { send(it, pv) }
    }

    private suspend fun startRealElection() {
        preVoteTerm = null
        persistTermAndVote(currentTerm + 1, transport.selfId)
        votesGranted.clear(); votesGranted += transport.selfId
        _role.value = RaftRole.Candidate
        _leader.value = null
        resetElectionTimeout()
        if (votesGranted.size >= clusterConfig.quorumSize) { becomeLeader(); return }
        emitTrace(RaftTraceEvent.Timeout(nextClock(), transport.selfId, currentTerm))
        val rv = RaftMessage.RequestVote(currentTerm, transport.selfId, lastLogIndex, lastLogTerm)
        otherVoters.forEach { peer ->
            emitTrace(RaftTraceEvent.RequestVote(nextClock(), transport.selfId, peer, currentTerm, lastLogIndex, lastLogTerm))
            send(peer, rv)
        }
    }
```
(The `startRealElection` body is exactly the previous `onElectionTimeout` body — copy it from git history of the file; do not invent new behavior.)

Implement `onPreVoteResponse` (replacing the Task 1 stub):
```kotlin
    private suspend fun onPreVoteResponse(from: NodeId, m: RaftMessage.PreVoteResponse) {
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (preVoteTerm == null || m.proposedTerm != preVoteTerm) return
        if (m.voteGranted) {
            preVotesGranted += from
            if (preVotesGranted.size >= clusterConfig.quorumSize) startRealElection()
        }
    }
```
Clear a pre-vote round when a leader appears: in `onAppendEntries` and `onInstallSnapshot`, where they set `_role.value = followerRole`, add `preVoteTerm = null`.

- [ ] **Step 4: Run to verify the tests pass**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*PreVoteTest*"`
Expected: PASS (all PreVoteTest cases).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS. (Existing election tests now go through the pre-vote path; they should still pass because in a connected, leaderless cluster every peer has `leaderAlive == false` and grants pre-votes.)

- [ ] **Step 6: Commit**

```bash
git add kuilt-raft/src
git commit -m "feat(kuilt-raft): pre-vote probe gates the term bump — no disruptive rejoin (#193)"
```

---

## Task 3: §4.2.3 leader-stickiness guard in `onRequestVote`

**Files:** Modify `internal/RaftEngine.kt`. Test `PreVoteTest.kt`.

- [ ] **Step 1: Write the failing test**

Append to `PreVoteTest.kt`:

```kotlin
    // §4.2.3: a follower within its leader-lease rejects a higher-term RequestVote WITHOUT adopting
    // the term. Asserted via trace: a VoteDenied(LeaderAlive) is emitted and the follower does NOT
    // emit BecomeFollower (which a term adoption would trigger).
    @Test
    fun stickyFollowerRejectsHigherTermVoteWithoutAdopting() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.nodeIds.first { it != leaderId }
        sim.nodes.getValue(follower).commitIndex.first { it >= 1L }   // it has heard the leader → leaderAlive
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(follower).trace.collect { trace += it } }
        sim.deliverRequestVote(to = follower, from = NodeId("v3"), term = 99L, lastLogIndex = 99L, lastLogTerm = 99L)
        sim.settle()
        assertTrue(trace.any { it is RaftTraceEvent.VoteDenied && it.reason == DenyReason.LeaderAlive },
            "sticky follower must deny with LeaderAlive: $trace")
        assertTrue(trace.none { it is RaftTraceEvent.BecomeFollower },
            "sticky follower must NOT adopt the higher term: $trace")
    }
```

Add a `deliverRequestVote` affordance to `RaftSimulation.kt` mirroring `deliverPreVote`:
```kotlin
suspend fun deliverRequestVote(to: NodeId, from: NodeId, term: Long, lastLogIndex: Long, lastLogTerm: Long) {
    val bytes = kotlinx.serialization.cbor.Cbor.encodeToByteArray<RaftMessage>(
        RaftMessage.RequestVote(term, from, lastLogIndex, lastLogTerm)
    )
    network.deliver(from = from, to = to, bytes = bytes)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*stickyFollowerRejectsHigherTermVoteWithoutAdopting*"`
Expected: FAIL — today `onRequestVote` adopts the higher term (emits `BecomeFollower`) and denies with `LogNotUpToDate`/`StaleTerm`, not `LeaderAlive`.

- [ ] **Step 3: Add the guard**

In `RaftEngine.kt`, at the very top of `onRequestVote(from, m)`, before the existing `if (m.term > currentTerm) stepDown(...)`:
```kotlin
        if (leaderAlive && m.term > currentTerm) {
            emitTrace(RaftTraceEvent.VoteDenied(nextClock(), transport.selfId, from, m.term, DenyReason.LeaderAlive))
            send(from, RaftMessage.RequestVoteResponse(currentTerm, false))
            return
        }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*PreVoteTest*"`
Expected: PASS.

- [ ] **Step 5: Full suite + commit**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS.
```bash
git add kuilt-raft/src
git commit -m "feat(kuilt-raft): §4.2.3 leader-stickiness guard in onRequestVote (#193)"
```

---

## Task 4: Property-based invariant + cross-platform

**Files:** Extend the existing PBT/chaos suite (find it: the synchronous PBT model + `ChaosTest`). Test additions only.

- [ ] **Step 1: Add a term-stability invariant**

In the chaos/PBT scenario, add: across a run that randomly partitions and heals single nodes, assert **no `BecomeFollower` is emitted by a node while it is the committed leader unless a genuinely higher-term entry exists** — i.e. a partitioned node never deposes a healthy leader. Concretely, track each node's `trace`; after the run, assert the max observed `Timeout.newTerm` did not grow on any node that was *only ever partitioned* (never legitimately contested). Mirror the existing PBT harness shape; do not invent a new framework.

Run: `./gradlew :kuilt-raft:jvmTest --tests "*Chaos*"` (adjust to real class names)
Expected: PASS.

- [ ] **Step 2: Cross-platform determinism**

The leader-lease uses `delay()` and the pre-vote round adds timer-driven transitions; validate under the non-JVM schedulers (see `docs/testing-coroutine-determinism.md`).

Run: `./gradlew :kuilt-raft:wasmJsTest :kuilt-raft:macosArm64Test`
Expected: PASS. If a flake appears, inject `UnconfinedTestDispatcher(testScheduler)` / advance virtual time rather than sleeping.

- [ ] **Step 3: Full multi-platform suite + commit**

Run: `./gradlew :kuilt-raft:allTests`
Expected: PASS.
```bash
git add kuilt-raft/src
git commit -m "test(kuilt-raft): PreVote term-stability invariant + cross-platform (#193)"
```

---

## Done criteria

- A partitioned voter never inflates its term (no real `Timeout`/term bump without a pre-vote quorum); on heal it does not depose the leader (`leaderTrace` has no `BecomeFollower`) and it rejoins.
- A node within its leader-lease rejects higher-term `RequestVote`s with `DenyReason.LeaderAlive` and does not adopt the term.
- Normal elections still complete via the pre-vote path; single-voter elects instantly; learners never pre-vote.
- `:kuilt-raft:allTests` green on all platforms; no persistence or config schema change.

## Follow-ups (filed)

- **#196** CheckQuorum — leader steps down when it can't reach a quorum (tidies the partitioned-old-leader stale-belief residual).
