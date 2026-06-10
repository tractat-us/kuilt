# kuilt-raft ReadIndex Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `RaftNode.readIndex()` — a linearizable read that confirms quorum freshness via a heartbeat round and returns a safe commit index, without writing to the log.

**Architecture:** QuorumRead only (LeaseRead deferred). The leader queues a pending read capturing the current `commitIndex` and the current heartbeat round, forces a heartbeat if idle, and resolves the read when a voter-quorum ACKs a round that began after the read was queued — reusing CheckQuorum's `recentVoterContacts` signal. The apply-catch-up wait is the caller's (the state machine is external); an `awaitRead` extension bundles the common pattern.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, `:kuilt-raft` engine actor, `FakeRaftNode` / in-memory engine harness for virtual-time tests.

**Spec:** `docs/superpowers/specs/2026-06-10-raft-readindex-design.md`

---

## File structure

| File | Responsibility | Change |
|------|----------------|--------|
| `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt` | Public `readIndex()` method (throwing default) + `awaitRead` extension | Modify |
| `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftTraceEvent.kt` | `ReadIndexConfirmed` trace event | Modify |
| `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/EngineCommand.kt` | `RequestReadIndex` command | Modify |
| `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt` | `pendingReads`/`heartbeatRound` state, `onRequestReadIndex`, `resolveReadsIfQuorumFresh`, `readIndex()` override, no-op gate, relinquish sweep, heartbeat-round bump | Modify |
| `kuilt-raft/src/commonTest/.../ReadIndexTest.kt` | The behaviour tests | Create |

Locate exact line ranges with `grep -n` at execution time; the engine evolves between plan and execution.

---

## Task 1: Trace event for a confirmed read

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftTraceEvent.kt`

- [ ] **Step 1: Read the existing event vocabulary**

Run: `grep -n "RaftTraceEvent\|data class\|data object" kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftTraceEvent.kt`
Note the sealed-hierarchy style (sealed interface/class + `data class`/`data object` members) and KDoc convention, so the new member matches.

- [ ] **Step 2: Add the event member**

Add alongside the existing members (after the last one — do not reorder):

```kotlin
    /**
     * The leader confirmed quorum freshness for a linearizable read at [readIndex] in [term].
     * No log entry is written for the read. Emitted once per pending read as it resolves.
     */
    public data class ReadIndexConfirmed(public val readIndex: Long, public val term: Long) : RaftTraceEvent
```

Match the actual base type name/modifiers found in Step 1 (e.g. `: RaftTraceEvent()` if it is a sealed class).

- [ ] **Step 3: Compile**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-raft:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftTraceEvent.kt
git commit -m "feat(kuilt-raft): ReadIndexConfirmed trace event"
```

---

## Task 2: Engine command

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/EngineCommand.kt`

- [ ] **Step 1: Read the command hierarchy**

Run: `grep -n "EngineCommand\|data class\|data object" kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/EngineCommand.kt`
Note the sealed base and member style (e.g. `QuorumCheck`).

- [ ] **Step 2: Add the command**

```kotlin
import kotlinx.coroutines.CompletableDeferred
// ... add as a member, after the last existing one:
    data class RequestReadIndex(val deferred: CompletableDeferred<Long>) : EngineCommand
```

- [ ] **Step 3: Compile**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-raft:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/EngineCommand.kt
git commit -m "feat(kuilt-raft): RequestReadIndex engine command"
```

---

## Task 3: Public `readIndex()` + `awaitRead` (throwing default first)

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ReadIndexTest.kt` (create)

- [ ] **Step 1: Write the failing test (non-leader throws)**

Create `ReadIndexTest.kt`. Use the existing harness — find it first:
Run: `grep -rln "FakeRaftNode\|newEngineHarness\|class .*RaftTest" kuilt-raft/src/commonTest | head` and read one neighbour test to copy its cluster-construction boilerplate exactly.

```kotlin
package us.tractat.kuilt.raft

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ReadIndexTest {
    @Test
    fun readIndexOnFollowerThrowsNotLeader() = runTest {
        // build a follower node via the same harness neighbour tests use
        val follower = /* harness: a node that is not the leader */ TODO("use harness from neighbour test")
        assertFailsWith<NotLeaderException> { follower.readIndex() }
    }
}
```

Replace the `TODO` with the real harness call discovered above — do not leave it. (For `FakeRaftNode`, a freshly constructed node defaults to follower/learner and inherits the throwing default.)

- [ ] **Step 2: Run it — fails to compile (`readIndex` undefined)**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-raft:jvmTest --tests "*ReadIndexTest.readIndexOnFollowerThrowsNotLeader"`
Expected: compilation failure — `readIndex` unresolved.

- [ ] **Step 3: Add the interface method + helper**

In `RaftNode.kt`, add the method to the `RaftNode` interface (after `changeMembership`, before `awaitLeadership` — group with the other suspend operations; do not reorder existing members):

```kotlin
    /**
     * Confirms this leader still holds a voter-quorum at its current term, then returns a
     * **read index**: a commit index `ri` such that any state machine that has applied through
     * `ri` reflects every write committed before this call. The read is linearizable once the
     * caller's apply loop reaches `ri`. The leader does **not** write to the log.
     *
     * Concurrent calls in the same heartbeat window share one round. A single-voter cluster
     * returns immediately. A freshly-elected leader suspends until its current-term no-op
     * commits before returning. Because the state machine is external (driven by [committed]),
     * the caller must wait until it has applied through the returned index — see [awaitRead].
     *
     * @throws NotLeaderException if this node is not the leader (including learners).
     * @throws LeadershipLostException if leadership is lost before the round confirms.
     */
    public suspend fun readIndex(): Long {
        throw NotLeaderException("readIndex: not the current leader")
    }
```

Add the extension at top level (after the `CoroutineScope.raftNode` factory), with the needed imports (`kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.flow.first` — `first` is already imported):

```kotlin
/**
 * Linearizable read barrier: confirms the read index via [RaftNode.readIndex], then suspends
 * until [applied] reaches it, returning that index. [applied] is the caller's own monotonic
 * applied-index flow, advanced as it consumes [RaftNode.committed].
 *
 * @throws NotLeaderException if not the leader; @throws LeadershipLostException if leadership is lost.
 */
public suspend fun RaftNode.awaitRead(applied: StateFlow<Long>): Long {
    val ri = readIndex()
    applied.first { it >= ri }
    return ri
}
```

- [ ] **Step 4: Run the test — passes**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-raft:jvmTest --tests "*ReadIndexTest.readIndexOnFollowerThrowsNotLeader"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ReadIndexTest.kt
git commit -m "feat(kuilt-raft): readIndex() contract + awaitRead helper (throwing default)"
```

---

## Task 4: Engine state + single-voter fast path

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`
- Test: `ReadIndexTest.kt`

- [ ] **Step 1: Write the failing test (single-voter returns commitIndex immediately)**

```kotlin
    @Test
    fun singleVoterReadIndexReturnsCommitIndexWithoutHeartbeat() = runTest {
        // 1-voter cluster; become leader; propose one entry so commitIndex advances.
        val leader = /* harness: single-voter leader */ TODO("harness")
        leader.awaitLeadership()
        val committed = leader.propose("x=1".encodeToByteArray())
        val ri = leader.readIndex()
        kotlin.test.assertTrue(ri >= committed.index)
    }
```

- [ ] **Step 2: Run it — fails (still throwing default / wrong value)**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.singleVoterReadIndexReturnsCommitIndexWithoutHeartbeat"`
Expected: FAIL (`NotLeaderException` from the default, since the engine has no override yet).

- [ ] **Step 3: Add engine state and the actor command, with single-voter fast path**

In `RaftEngine.kt`:

Add fields near the CheckQuorum state (`recentVoterContacts`, `quorumCheckJob`):

```kotlin
    private data class PendingRead(val readIndex: Long, val sinceRound: Long, val deferred: CompletableDeferred<Long>)
    private val pendingReads = mutableListOf<PendingRead>()
    private var heartbeatRound = 0L
```

Override the public method on the engine (the engine implements `RaftNode`):

```kotlin
    override suspend fun readIndex(): Long {
        val deferred = CompletableDeferred<Long>()
        cmd.send(EngineCommand.RequestReadIndex(deferred))
        return deferred.await()
    }
```

Add the actor branch in `startActor`'s `when` (next to `QuorumCheck`):

```kotlin
            is EngineCommand.RequestReadIndex -> onRequestReadIndex(c.deferred)
```

Add the handler (no-op gate added in Task 5 — for now, single-voter + leader check):

```kotlin
    private fun onRequestReadIndex(deferred: CompletableDeferred<Long>) {
        if (role.value !is RaftRole.Leader) {
            deferred.completeExceptionally(NotLeaderException("readIndex: not the current leader")); return
        }
        val ri = _commitIndex.value
        if (clusterConfig.quorumSize == 1) { deferred.complete(ri); return }
        pendingReads += PendingRead(ri, heartbeatRound, deferred)
        // quorum confirmation wired in Task 5
    }
```

(Use the engine's actual role accessor — `grep -n "_role\|role.value\|is RaftRole.Leader" RaftEngine.kt` and match it. Use the actual command channel name — `grep -n "cmd\b\|Channel<EngineCommand>" RaftEngine.kt`.)

- [ ] **Step 4: Run the test — passes**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.singleVoterReadIndexReturnsCommitIndexWithoutHeartbeat"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/.../internal/RaftEngine.kt kuilt-raft/src/commonTest/.../ReadIndexTest.kt
git commit -m "feat(kuilt-raft): readIndex engine state + single-voter fast path"
```

---

## Task 5: Quorum-confirmation round + multi-voter read-your-writes

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`
- Test: `ReadIndexTest.kt`

- [ ] **Step 1: Write the failing test (3-voter read-your-writes, no log growth)**

```kotlin
    @Test
    fun multiVoterReadIndexReflectsCommittedWriteWithoutLogWrite() = runTest {
        // 3-voter cluster; elect a leader.
        val leader = /* harness leader */ TODO("harness")
        leader.awaitLeadership()
        val before = /* leader.log.size or lastLogIndex via harness/inspection */ TODO("capture log length")
        val committed = leader.propose("x=1".encodeToByteArray())
        val ri = leader.readIndex()                 // must confirm via a heartbeat round
        kotlin.test.assertTrue(ri >= committed.index)
        val after = /* same measure as `before`, plus the one propose */ TODO("capture log length")
        // exactly one new entry (the propose), none for the read:
        kotlin.test.assertEquals(before + 1, after)
    }
```

If the harness drives heartbeats on virtual time, advance the scheduler (`advanceUntilIdle()` / `advanceTimeBy(heartbeatInterval)`) between `propose` and the `readIndex()` await as neighbour CheckQuorum tests do; copy their pattern.

- [ ] **Step 2: Run it — fails (read never resolves / times out, or compiles but hangs without confirmation)**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.multiVoterReadIndexReflectsCommittedWriteWithoutLogWrite"`
Expected: FAIL (read deferred never completes — no confirmation path yet).

- [ ] **Step 3: Bump the heartbeat round and resolve reads on quorum ACK**

In the heartbeat broadcast path (`grep -n "heartbeat\|sendAppendEntries\|broadcastAppend" RaftEngine.kt`), increment the round once per broadcast:

```kotlin
        heartbeatRound++
```

In `onAppendEntriesResponse`, after the existing `recentVoterContacts += from` (added by CheckQuorum #196), call:

```kotlin
        resolveReadsIfQuorumFresh()
```

Add the resolver, reusing the CheckQuorum quorum math:

```kotlin
    private fun resolveReadsIfQuorumFresh() {
        if (role.value !is RaftRole.Leader || pendingReads.isEmpty()) return
        val reachable = recentVoterContacts.count { it in clusterConfig.voters } + 1
        if (reachable < clusterConfig.quorumSize) return
        val now = heartbeatRound
        val ready = pendingReads.filter { now > it.sinceRound }
        if (ready.isEmpty()) return
        pendingReads.removeAll(ready)
        ready.forEach {
            emitTrace(RaftTraceEvent.ReadIndexConfirmed(it.readIndex, currentTerm))
            it.deferred.complete(it.readIndex)
        }
    }
```

(Match the actual trace-emit helper — `grep -n "emitTrace\|trace\b\|_trace" RaftEngine.kt` — and the actual `currentTerm` accessor.) Ensure `onRequestReadIndex` forces a heartbeat when idle so a read issued between ticks doesn't wait a full interval: if the engine has an immediate-heartbeat trigger, call it; otherwise the next scheduled heartbeat (≤ `heartbeatInterval`) confirms it — note which in a code comment.

- [ ] **Step 4: Run the test — passes**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.multiVoterReadIndexReflectsCommittedWriteWithoutLogWrite"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/.../internal/RaftEngine.kt kuilt-raft/src/commonTest/.../ReadIndexTest.kt
git commit -m "feat(kuilt-raft): confirm readIndex via heartbeat-round quorum ACK"
```

---

## Task 6: Leader-completeness gate (fresh-leader no-op)

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`
- Test: `ReadIndexTest.kt`

- [ ] **Step 1: Write the failing test (read issued before no-op commits waits for it)**

```kotlin
    @Test
    fun freshLeaderReadIndexWaitsForCurrentTermNoOp() = runTest {
        // Force an election; issue readIndex() as soon as role==Leader, before the no-op commits.
        val leader = /* harness, with control over no-op commit timing */ TODO("harness")
        leader.awaitLeadership()
        val noOpIndex = /* the becomeLeader no-op index, via harness/inspection */ TODO("capture")
        val ri = leader.readIndex()
        kotlin.test.assertTrue(ri >= noOpIndex)     // never returns a pre-no-op stale index
    }
```

Inspect how `becomeLeader` records the no-op index (`grep -n "noOp\|isNoOp\|noOpIndex" RaftEngine.kt`) and expose/capture it the way neighbour tests assert on internal indices.

- [ ] **Step 2: Run it — fails (read may return before no-op commit)**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.freshLeaderReadIndexWaitsForCurrentTermNoOp"`
Expected: FAIL or flaky (returns an index `< noOpIndex`).

- [ ] **Step 3: Add the no-op gate to `onRequestReadIndex`**

Track the current-term no-op index when `becomeLeader` appends it (a field e.g. `private var currentTermNoOpIndex = 0L`, set in `becomeLeader`, reset on relinquish). Then in `onRequestReadIndex`, before capturing `ri`, hold the request until `_commitIndex.value >= currentTermNoOpIndex`:

```kotlin
        if (_commitIndex.value < currentTermNoOpIndex) {
            // park: re-deliver this request once commitIndex reaches the no-op.
            pendingNoOpGate += { onRequestReadIndex(deferred) }
            return
        }
```

where `pendingNoOpGate` is a `mutableListOf<() -> Unit>()` drained in the commit-advance path (`grep -n "_commitIndex.value =" RaftEngine.kt`) right after `commitIndex` is bumped: snapshot and clear the list, invoke each. (Pick the engine's existing idiom for deferred work if one exists — e.g. how pending proposals are released on commit — and mirror it instead of inventing a list if that is cleaner.)

- [ ] **Step 4: Run the test — passes**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.freshLeaderReadIndexWaitsForCurrentTermNoOp"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/.../internal/RaftEngine.kt kuilt-raft/src/commonTest/.../ReadIndexTest.kt
git commit -m "feat(kuilt-raft): gate readIndex on current-term no-op commit (§8)"
```

---

## Task 7: Fail pending reads on leadership loss

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`
- Test: `ReadIndexTest.kt`

- [ ] **Step 1: Write the failing test (partition → LeadershipLostException)**

```kotlin
    @Test
    fun readIndexFailsWhenLeaderLosesQuorum() = runTest {
        // 3-voter cluster; elect leader; partition it from the other two; issue readIndex().
        val leader = /* harness leader */ TODO("harness")
        leader.awaitLeadership()
        // partition: drop messages to/from peers (copy CheckQuorum #196 partition helper)
        val read = kotlinx.coroutines.async { leader.readIndex() }
        // advance past the quorum-check window so CheckQuorum steps it down
        // advanceTimeBy(electionTimeoutMax) / advanceUntilIdle() per neighbour tests
        assertFailsWith<LeadershipLostException> { read.await() }
    }
```

- [ ] **Step 2: Run it — fails (read hangs instead of failing)**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.readIndexFailsWhenLeaderLosesQuorum"`
Expected: FAIL (deferred never completes; test times out or `UncompletedCoroutinesError`).

- [ ] **Step 3: Sweep pending reads in the relinquish path**

In `relinquishToFollower` (the shared step-down body from #196), after the existing `failPending(...)`:

```kotlin
        pendingReads.forEach {
            it.deferred.completeExceptionally(LeadershipLostException("lost leadership before read confirmed"))
        }
        pendingReads.clear()
```

Also clear `pendingReads` and reset `heartbeatRound = 0` / `currentTermNoOpIndex` in `becomeLeader` (fresh leadership starts clean). Confirm `becomeLeader` already does the analogous reset for `recentVoterContacts` and mirror it.

- [ ] **Step 4: Run the test — passes**

Run: `... :kuilt-raft:jvmTest --tests "*ReadIndexTest.readIndexFailsWhenLeaderLosesQuorum"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/.../internal/RaftEngine.kt kuilt-raft/src/commonTest/.../ReadIndexTest.kt
git commit -m "feat(kuilt-raft): fail in-flight readIndex on leadership loss"
```

---

## Task 8: Concurrency/batching + `awaitRead` + learner tests

**Files:**
- Test: `ReadIndexTest.kt`

- [ ] **Step 1: Add the remaining behaviour tests**

```kotlin
    @Test
    fun concurrentReadsShareOneQuorumRound() = runTest {
        val leader = /* 3-voter leader */ TODO("harness")
        leader.awaitLeadership()
        leader.propose("x=1".encodeToByteArray())
        val reads = List(5) { kotlinx.coroutines.async { leader.readIndex() } }
        // advance one heartbeat window
        val results = reads.map { it.await() }
        kotlin.test.assertTrue(results.all { it == results.first() })   // same read index, one round
    }

    @Test
    fun awaitReadReturnsAfterAppliedReachesReadIndex() = runTest {
        val leader = /* single-voter or 3-voter leader */ TODO("harness")
        leader.awaitLeadership()
        leader.propose("x=1".encodeToByteArray())
        val applied = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val job = kotlinx.coroutines.async { leader.awaitRead(applied) }
        // not yet applied — must not complete
        kotlin.test.assertFalse(job.isCompleted)
        applied.value = Long.MAX_VALUE             // simulate apply loop catching up
        val ri = job.await()
        kotlin.test.assertTrue(ri >= 1)
    }

    @Test
    fun readIndexOnLearnerThrowsNotLeader() = runTest {
        val learner = /* harness learner node */ TODO("harness")
        assertFailsWith<NotLeaderException> { learner.readIndex() }
    }
```

- [ ] **Step 2: Run the full test class**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-raft:jvmTest --tests "*ReadIndexTest"`
Expected: all PASS.

- [ ] **Step 3: Commit**

```bash
git add kuilt-raft/src/commonTest/.../ReadIndexTest.kt
git commit -m "test(kuilt-raft): readIndex batching, awaitRead, learner cases"
```

---

## Task 9: Full build across platforms

**Files:** none (verification)

- [ ] **Step 1: Run the full build**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build`
Expected: BUILD SUCCESSFUL — `jvmTest` hides Android-variant, wasmJs, and `:kuilt-raft-test` (`FakeRaftNode` inherits the throwing `readIndex` default) failures; the full build catches them. Watch for `explicitApi()` violations on the new public `readIndex`/`awaitRead`/`ReadIndexConfirmed` (all must carry explicit `public`).

- [ ] **Step 2: Fix any cross-platform / explicitApi issues, then re-run.** Commit fixes individually.

- [ ] **Step 3: Final commit if any fixes were needed**

```bash
git add -A && git commit -m "fix(kuilt-raft): cross-platform + explicitApi for readIndex"
```

---

## Self-review

- **Spec coverage:** QuorumRead-only (T3–T5), caller-waits + `awaitRead` (T3, T8), batch-on-heartbeat/no-new-wire (T5), no-op gate §8 (T6), single-voter fast path (T4), non-leader/learner throws (T3, T8), mid-round loss (T7), `ReadIndexConfirmed` trace (T1), no `RaftConfig`/`RaftStorage` change (none added). Acceptance criteria 1–7 each map to a test in T4–T8. ✓
- **Type consistency:** `readIndex(): Long`, `awaitRead(applied: StateFlow<Long>): Long`, `ReadIndexConfirmed(readIndex, term)`, `RequestReadIndex(deferred)`, `PendingRead(readIndex, sinceRound, deferred)`, `pendingReads`, `heartbeatRound`, `currentTermNoOpIndex`, `resolveReadsIfQuorumFresh`, `onRequestReadIndex` — used identically across tasks. ✓
- **Placeholders:** the `TODO("harness")` markers are deliberate — they mark where the executor must substitute the project's actual test-harness construction, which differs per test file; each is paired with a `grep` to find the real call. Every production-code step is concrete.
- **Out of scope:** LeaseRead, read-forwarding, applied-index feedback channel — none implemented.
