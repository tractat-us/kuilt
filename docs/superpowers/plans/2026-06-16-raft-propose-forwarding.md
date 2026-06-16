# RaftNode.propose leader-forwarding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `RaftNode.propose` work from any peer by forwarding the command to the current leader over the existing raft transport, so a non-leader player can submit a move; then build the two-player tic-tac-toe + chat example that consumes it.

**Architecture:** Forwarding is internal to `kuilt-raft`. Two new internal `RaftMessage` variants (`Forward`, `ForwardResponse`) ride the existing opaque-bytes transport — the public `RaftTransport` SPI and `SeamRaftTransport` are untouched. A follower's `propose` registers a correlation id and either sends `Forward` to the known leader or queues until one is elected (cancellable wait). The leader runs its normal propose path and replies with the committed index/term. The leader stays the single appender.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (actor loop over a `Channel`), kotlinx.serialization CBOR (wire), `raftSim` in-memory test harness, JUnit-style `kotlin.test`.

**Spec:** `docs/superpowers/specs/2026-06-16-raft-propose-forwarding-design.md`

**Key files (read before starting):**
- `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftMessage.kt` — internal sealed RPC set.
- `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt` — the engine. Anchors: actor loop `startActor()` (~L326–346), `onMessage` dispatcher (L1630–1640), `send()` (L1642), `onPropose` (L1237–1261), `propose` (L1263–1271), `becomeLeader()` (L613), `_leader: StateFlow<NodeId?>` (L66), `pending` map pattern (L1257).
- `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt` — `propose` KDoc (L196–202).
- `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftExceptions.kt` — `NotLeaderException`, `LeadershipLostException`.
- `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/TurnSequencer.kt` — `NotYourTurnException` mapping (L101–111).
- `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftTestFixtures.kt` + `RaftSimulation.kt` — harness: `raftRunTest`, `raftSim(scope, nodeScope, n)`, `awaitLeader(sim)`, `sim.nodes[id]`, `sim.nodeIds`, `sim.awaitCommit(idx, on=…)`, `sim.awaitRole(id, role)`, `sim.partition(a,b)`/`sim.heal()`/`sim.partitionOff(id)`, `sim.leader()`.

**Build commands:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` first, then:
- One raft test class: `./gradlew :kuilt-raft:jvmTest --tests "*ForwardingTest"`
- kuilt-game: `./gradlew :kuilt-game:jvmTest`
- examples: `./gradlew :examples:jvmTest`
- Lint (real check): `./gradlew detektAll`
- Full: `./gradlew build`

**Determinism rules (non-negotiable):** raft tests run under `StandardTestDispatcher` via `raftRunTest`; use `FAST_RAFT_CONFIG` (seeded RNG); node coroutines go on `backgroundScope`; never `Dispatchers.*`/`GlobalScope` in test sources; bound every await with the harness helpers (no raw `delay` polling). New `suspend` catches must rethrow `CancellationException`.

---

## Task 1: Add forwarding wire messages

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftMessage.kt`

- [ ] **Step 1: Add the message variants and outcome type**

Append inside the `RaftMessage` sealed interface (after `TimeoutNow`, before the closing brace), and add `ForwardOutcome` as a sibling top-level `@Serializable` sealed interface in the same file:

```kotlin
    /**
     * Client-proposal forwarding (Raft paper §8): a follower relays a `propose` command to the
     * current leader, which appends it on the follower's behalf. [clientRequestId] is the
     * follower-local correlation nonce echoed back in [ForwardResponse]; it is NOT written to the
     * log, so committed entries are unchanged.
     */
    @Serializable
    data class Forward(
        val clientRequestId: Long,
        val command: ByteArray,
    ) : RaftMessage {
        // command is a ByteArray (reference equals in generated equals); this is a transport
        // envelope only — identity equality is never meaningful (same rationale as AppendEntries).
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = clientRequestId.hashCode()
    }

    /** Leader's reply to [Forward]: the proposal's fate, correlated by [clientRequestId]. */
    @Serializable
    data class ForwardResponse(
        val clientRequestId: Long,
        val outcome: ForwardOutcome,
    ) : RaftMessage
```

```kotlin
/** Outcome of a forwarded proposal, carried in [RaftMessage.ForwardResponse]. */
@Serializable
internal sealed interface ForwardOutcome {
    /** Committed at [index] in [term]. */
    @Serializable
    data class Committed(val index: Long, val term: Long) : ForwardOutcome

    /** The target was not (or no longer) the leader; the caller should retry. */
    @Serializable
    data object NotLeader : ForwardOutcome

    /** The proposal failed for a non-retryable reason. */
    @Serializable
    data object Failed : ForwardOutcome
}
```

- [ ] **Step 2: Compile**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-raft:compileKotlinJvm`
Expected: BUILD SUCCESSFUL (no behavior yet; new types only).

- [ ] **Step 3: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftMessage.kt
git commit -m "feat(raft): add Forward/ForwardResponse wire messages"
```

---

## Task 2: Engine forwarding — happy path (leader known)

A follower's `propose` forwards to the known leader; the leader proposes locally and replies; the follower completes its `propose` with the committed entry.

**Files:**
- Modify: `kuilt-raft/.../internal/RaftEngine.kt` (state fields; `onPropose`; new `onForward`/`onForwardResponse`; `onMessage` dispatch)
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftProposeForwardingTest.kt` (new)

- [ ] **Step 1: Write the failing test (follower propose commits)**

Create `RaftProposeForwardingTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

internal class RaftProposeForwardingTest {

    @Test
    fun followerPropose_forwardsToLeader_andCommits() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 2)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followerId = sim.nodeIds.first { it != leaderId }
        val follower = sim.nodes.getValue(followerId)

        // Proposing on the FOLLOWER must succeed by forwarding to the leader.
        val entry = withTimeout(5.seconds) { follower.propose("hello".encodeToByteArray()) }

        assertEquals(1L, entry.index)
        assertEquals("hello", entry.command.decodeToString())
        // Entry committed on both peers.
        sim.awaitCommit(1L, on = sim.nodeIds)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*RaftProposeForwardingTest"`
Expected: FAIL — `follower.propose` currently completes exceptionally with `NotLeaderException` (`onPropose` L1238–1240).

- [ ] **Step 3: Add forwarding state fields**

In `RaftEngine.kt`, near the other mutable engine state (e.g. just after the `pending` field used at L1257), add:

```kotlin
    // ── Client-proposal forwarding (follower side) ──────────────────────────────
    /** Monotonic correlation nonce for outbound forwards. */
    private var nextForwardId: Long = 0L
    /** reqId -> (caller's deferred, original command) for forwards awaiting a ForwardResponse. */
    private val forwardedProposals = mutableMapOf<Long, Pair<CompletableDeferred<LogEntry>, ByteArray>>()
    /** reqIds queued because no leader was known yet; flushed when a leader appears. */
    private val waitingForLeader = mutableListOf<Long>()
```

(Add `import kotlinx.coroutines.CompletableDeferred` if not already imported — it is used by `EngineCommand`, so likely present.)

- [ ] **Step 4: Rewrite `onPropose` follower branch to forward**

Replace the leader-only guard at the top of `onPropose` (L1238–1241) so the non-leader case forwards instead of failing. The transfer-in-flight guard (L1245–1248) stays — it applies only when *this* node is the leader:

```kotlin
    private suspend fun onPropose(command: ByteArray, response: CompletableDeferred<LogEntry>) {
        if (_role.value !is RaftRole.Leader) {
            // Follower: forward to the leader (Raft §8). Wait, cancellably, if none is known yet.
            val id = nextForwardId++
            forwardedProposals[id] = response to command
            response.invokeOnCompletion { forwardedProposals.remove(id) } // cancellation/cleanup
            val leaderId = _leader.value
            if (leaderId != null && leaderId != transport.selfId) {
                send(leaderId, RaftMessage.Forward(id, command))
            } else {
                waitingForLeader += id
            }
            return
        }
        if (transferTarget != null) {
            response.completeExceptionally(NotLeaderException("leadership transfer in flight to ${transferTarget!!.value}"))
            return
        }
        // ... existing leader append path unchanged (index/entry/log/pending/sendAppendEntries/tryAdvanceLeaderCommit) ...
```

Keep the entire existing leader append body (L1249–1260) as-is below the `transferTarget` guard.

- [ ] **Step 5: Add `onForward` (leader side) and `onForwardResponse` (follower side)**

Add these methods near `onPropose`:

```kotlin
    /** Leader handles a forwarded proposal: run the normal propose path, reply with its fate. */
    private suspend fun onForward(from: NodeId, m: RaftMessage.Forward) {
        val d = CompletableDeferred<LogEntry>()
        onPropose(m.command, d)            // actor-side: appends + registers in `pending` (or rejects)
        scope.launch {                      // await commit off the actor loop, then reply
            val outcome = try {
                val e = d.await()
                ForwardOutcome.Committed(e.index, e.term)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is NotLeaderException || e is LeadershipLostException) ForwardOutcome.NotLeader
                else ForwardOutcome.Failed
            }
            send(from, RaftMessage.ForwardResponse(m.clientRequestId, outcome))
        }
    }

    /** Follower handles the leader's reply to a forward it sent. */
    private fun onForwardResponse(from: NodeId, m: RaftMessage.ForwardResponse) {
        val (deferred, command) = forwardedProposals.remove(m.clientRequestId) ?: return
        when (val o = m.outcome) {
            is ForwardOutcome.Committed -> deferred.complete(LogEntry(o.index, o.term, command))
            ForwardOutcome.NotLeader, ForwardOutcome.Failed ->
                deferred.completeExceptionally(LeadershipLostException("forwarded proposal was not committed; retry"))
        }
    }
```

Add imports if missing: `kotlinx.coroutines.launch`, `kotlinx.coroutines.CancellationException`.

- [ ] **Step 6: Wire the dispatcher**

In `onMessage` (L1630–1640) add two branches:

```kotlin
        is RaftMessage.Forward                 -> onForward(from, m)
        is RaftMessage.ForwardResponse         -> onForwardResponse(from, m)
```

- [ ] **Step 7: Run the test — expect PASS**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*RaftProposeForwardingTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add kuilt-raft/src/commonMain/.../internal/RaftEngine.kt kuilt-raft/src/commonTest/.../RaftProposeForwardingTest.kt
git commit -m "feat(raft): forward follower proposals to the leader (known-leader path)"
```

---

## Task 3: Wait (cancellable) for a leader when none is known

A follower that proposes before any leader exists queues the forward and flushes it when a leader appears.

**Files:**
- Modify: `kuilt-raft/.../internal/RaftEngine.kt` (add `flushWaitingForLeader`; call it from the actor loop)
- Test: add to `RaftProposeForwardingTest.kt`

- [ ] **Step 1: Write the failing test (propose with no leader, then elect)**

Add to `RaftProposeForwardingTest.kt`:

```kotlin
    @Test
    fun propose_withNoLeaderYet_waitsThenCommitsOnceElected() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 2)
        // Split-brain: no quorum, so no leader can be elected.
        sim.partition(setOf(sim.nodeIds[0]), setOf(sim.nodeIds[1]))

        // Propose on a node while it cannot reach a leader — must suspend, not throw.
        val proposing = sim.nodes.getValue(sim.nodeIds[0])
        val deferred = kotlinx.coroutines.async {
            withTimeout(8.seconds) { proposing.propose("queued".encodeToByteArray()) }
        }

        // Heal — an election succeeds, the queued proposal flushes and commits.
        sim.heal()
        val entry = deferred.await()
        assertEquals("queued", entry.command.decodeToString())
        sim.awaitCommit(entry.index, on = sim.nodeIds)
    }
```

Add `import kotlinx.coroutines.async`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*RaftProposeForwardingTest"`
Expected: FAIL — the queued forward is never sent (nothing drains `waitingForLeader`), so `withTimeout` trips.

- [ ] **Step 3: Add the flush method**

Add to `RaftEngine.kt` near the forwarding methods:

```kotlin
    /**
     * Drain forwards queued while no leader was known. If we are now the leader, propose them
     * locally; otherwise send them to the current leader. No-op when nothing is queued or no
     * leader is known yet.
     */
    private suspend fun flushWaitingForLeader() {
        if (waitingForLeader.isEmpty()) return
        val leaderId = _leader.value
        val amLeader = _role.value is RaftRole.Leader
        if (!amLeader && (leaderId == null || leaderId == transport.selfId)) return
        val batch = waitingForLeader.toList()
        waitingForLeader.clear()
        for (id in batch) {
            val pair = forwardedProposals[id] ?: continue   // caller cancelled meanwhile
            if (amLeader) {
                forwardedProposals.remove(id)               // leader path completes via `pending`
                onPropose(pair.second, pair.first)
            } else {
                send(leaderId!!, RaftMessage.Forward(id, pair.second))
            }
        }
    }
```

- [ ] **Step 4: Call the flush from the actor loop**

In `startActor()` (L329–346), call `flushWaitingForLeader()` once per command after the `when` block, so any state change that established/changed a leader drains the queue:

```kotlin
                for (c in cmd) {
                    when (c) {
                        // ... existing branches unchanged ...
                        is EngineCommand.Close            -> { cmd.close(); break }
                    }
                    flushWaitingForLeader()
                }
```

- [ ] **Step 5: Run the test — expect PASS**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*RaftProposeForwardingTest"`
Expected: PASS (both forwarding tests).

- [ ] **Step 6: Commit**

```bash
git add kuilt-raft/src/commonMain/.../internal/RaftEngine.kt kuilt-raft/src/commonTest/.../RaftProposeForwardingTest.kt
git commit -m "feat(raft): queue forwards until a leader is elected, then flush"
```

---

## Task 4: Cancellation cleanup + queued-then-self-elected

Two correctness edges: a cancelled `propose` must not leak map state, and a node holding queued forwards that itself wins the election must commit them locally.

**Files:**
- Test: add to `RaftProposeForwardingTest.kt` (no new production code expected — `invokeOnCompletion` from Task 2 and `flushWaitingForLeader`'s `amLeader` branch from Task 3 should already cover these; the tests prove it)

- [ ] **Step 1: Write the failing/affirming tests**

```kotlin
    @Test
    fun cancelledForwardingPropose_doesNotCommitLater() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 2)
        sim.partition(setOf(sim.nodeIds[0]), setOf(sim.nodeIds[1])) // no leader
        val proposing = sim.nodes.getValue(sim.nodeIds[0])

        val job = kotlinx.coroutines.launch { proposing.propose("doomed".encodeToByteArray()) }
        job.cancelAndJoin()       // cancel while it waits for a leader

        sim.heal()
        val leader = awaitLeader(sim)
        // A real proposal still works and lands at index 1 — the cancelled one never appended.
        val entry = withTimeout(5.seconds) { leader.propose("real".encodeToByteArray()) }
        assertEquals(1L, entry.index)
        assertEquals("real", entry.command.decodeToString())
    }

    @Test
    fun queuedForward_onNodeThatBecomesLeader_commitsLocally() = raftRunTest(timeout = 10.seconds) {
        // Single-voter cluster: the only node is guaranteed to become leader. Propose racing the
        // first election must still commit once it becomes leader (flush's amLeader branch).
        val sim = raftSim(this, backgroundScope, n = 1)
        val only = sim.nodes.getValue(sim.nodeIds[0])
        val entry = withTimeout(5.seconds) { only.propose("solo".encodeToByteArray()) }
        assertEquals("solo", entry.command.decodeToString())
        sim.awaitCommit(entry.index, on = sim.nodeIds)
    }
```

Add `import kotlinx.coroutines.cancelAndJoin`.

- [ ] **Step 2: Run the tests**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*RaftProposeForwardingTest"`
Expected: PASS. If `cancelledForwardingPropose…` fails because a stale map entry flushed after cancel, verify the `response.invokeOnCompletion { forwardedProposals.remove(id) }` from Task 2 Step 4 runs and that `flushWaitingForLeader` skips ids absent from `forwardedProposals` (it does: `?: continue`). If `queuedForward_onNodeThatBecomesLeader…` fails, confirm `flushWaitingForLeader` is invoked after `ElectionTimeout`/`becomeLeader` (it is, via the per-command call in Task 3 Step 4).

- [ ] **Step 3: Commit**

```bash
git add kuilt-raft/src/commonTest/.../RaftProposeForwardingTest.kt
git commit -m "test(raft): cover forwarding cancellation cleanup and self-election flush"
```

---

## Task 5: Update `RaftNode.propose` docs + audit existing follower-propose assertions

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt` (propose KDoc, L196–202; and any class-level KDoc that says "call on the leader")
- Modify: any existing test asserting a *follower* `propose` throws `NotLeaderException`

- [ ] **Step 1: Find existing assertions that now change**

Run: `grep -rn "NotLeaderException\|propose" kuilt-raft/src/commonTest | grep -i "follow\|notleader"`
Read each hit. Tests that assert a **leader** rejects during transfer/close stay valid. Any test asserting a **follower** `propose` throws `NotLeaderException` must be updated to assert it now forwards and commits (or deleted if redundant with Task 2). Update them in place.

- [ ] **Step 2: Update the `propose` KDoc**

In `RaftNode.kt`, change the propose KDoc to describe forwarding. Replace the "Only the leader can propose; all other roles throw [NotLeaderException]" language (around L196) with:

```kotlin
    /**
     * Proposes [command] for replication and suspends until a quorum commits it, returning the
     * committed [LogEntry].
     *
     * Callable from **any** node. On the leader it appends directly; on a follower it forwards the
     * command to the current leader over the transport (Raft §8 client interaction) and awaits the
     * commit. If no leader is known yet, it waits (cancellably) until one is elected.
     *
     * @throws NotLeaderException only in terminal cases (the node is closed).
     * @throws LeadershipLostException if a forwarded proposal is rejected by the target (e.g. it
     *   stepped down); the caller may retry. Cancel the calling coroutine (or wrap in `withTimeout`)
     *   to abandon a proposal that is still waiting for a leader or a commit.
     */
```

- [ ] **Step 3: Run the raft suite**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS (forwarding tests + the whole existing suite green; any follower-propose assertions updated).

- [ ] **Step 4: Commit**

```bash
git add kuilt-raft/src/commonMain/.../RaftNode.kt kuilt-raft/src/commonTest/...
git commit -m "docs(raft): propose() forwards from any node; update follower-propose tests"
```

---

## Task 6: Remove `TurnSequencer.NotYourTurnException`

With forwarding, a follower's `propose` no longer fails — `NotYourTurnException` (raft leadership mis-named as a game turn) is dead.

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/TurnSequencer.kt`
- Check: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/` for the `NotYourTurnException` declaration and any `@sample`/KDoc referencing it
- Test: `kuilt-game` test that a non-leader `TurnSequencer.propose` commits

- [ ] **Step 1: Write the failing test**

Find the existing TurnSequencer test file (`grep -rln "class TurnSequencer" kuilt-game/src/commonTest`). Add a test using two real raft nodes (mirror the `raftSim` pattern, or the existing kuilt-game test harness if one wraps RaftNode). Minimal shape — propose via a `TurnSequencer` built over the **follower** node and assert it commits:

```kotlin
    @Test
    fun propose_fromFollower_commits() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 2)
        val leader = awaitLeader(sim)
        val followerId = sim.nodeIds.first { sim.nodes[it] !== leader }
        val seq = TurnSequencer(sim.nodes.getValue(followerId), kotlinx.serialization.serializer<Int>())
        val indexed = withTimeout(5.seconds) { seq.propose(7) }
        assertEquals(7, indexed.action)
    }
```

(If `kuilt-game` test sources cannot see `raftSim` from `kuilt-raft` commonTest, add `kuilt-game`'s test dependency on `:kuilt-raft-test` or replicate the 2-node wiring with `InMemoryRaftNetwork`; check what `kuilt-game`'s existing tests already use.)

- [ ] **Step 2: Run to verify it fails/compiles**

Run: `./gradlew :kuilt-game:jvmTest --tests "*TurnSequencer*"`
Expected: FAIL or compile error referencing `NotYourTurnException` (still present).

- [ ] **Step 3: Remove `NotYourTurnException` and simplify `propose`**

In `TurnSequencer.kt` replace the propose body (L101–111) with:

```kotlin
    public suspend fun propose(action: A): IndexedAction<A> {
        val entry = node.propose(encode(action))
        // Re-wrap the caller's action object (avoids a redundant decode round-trip).
        return IndexedAction(entry.index, action)
    }
```

Delete the `NotYourTurnException` class declaration and its imports of `NotLeaderException`/`LeadershipLostException` if now unused. Update the `@throws NotYourTurnException` / `@throws TurnLostInFlightException` KDoc lines (L97–99) to reflect that `propose` now forwards and only surfaces `LeadershipLostException` on a rejected forward. Keep `TurnLostInFlightException` only if it is still thrown anywhere; if it solely wrapped `LeadershipLostException` here and nothing else uses it, remove it too. Grep first: `grep -rn "NotYourTurnException\|TurnLostInFlightException" kuilt-game/src`.

- [ ] **Step 4: Run the test — expect PASS**

Run: `./gradlew :kuilt-game:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-game/src
git commit -m "feat(game)!: TurnSequencer.propose works from any peer; remove NotYourTurnException"
```

---

## Task 7: Two-player tic-tac-toe + chat example (plays to a result)

**Files:**
- Modify: `examples/src/test/kotlin/us/tractat/kuilt/examples/TicTacToeChatTest.kt` (replace the single-leader version from #481 with the two-player, win/draw version) OR add `TicTacToeTwoPlayerTest.kt` alongside it — prefer **replacing** so there's one canonical combined example.
- Maybe modify: `examples/build.gradle.kts` (already has `:kuilt-raft` + `:kuilt-core`; ensure `:kuilt-game`, `:kuilt-crdt` deps present)

- [ ] **Step 1: Write the example test**

Two peers over `InMemoryLoom`; `MuxSeam` channel 0 = raft, channel 1 = chat. Real raft node + `TurnSequencer` per peer; one stable leader. Each peer collects `committed`, applies moves to a local 3×3 board, derives whose turn from the move count, and the player-to-move calls `propose` (forwarding lets the non-leader peer move). Scripted moves give X a top-row win. Both peers detect the win and stop. Assertions: identical move sequence, identical final board, identical outcome, converged chat.

Use the existing `TicTacToeChatTest.kt` (from PR #481, on the branch as the starting point) for the construction boilerplate (loom/mux/cluster/raft/replicator wiring), and change the play loop to the two-player form below. Board + win/draw are example code:

```kotlin
    private enum class Mark { X, O }
    private data class Move(val row: Int, val col: Int)   // @Serializable

    // Pure helpers — application semantics, not library:
    private fun winner(board: Map<Move, Mark>): Mark? {
        val lines = listOf(
            listOf(Move(0,0),Move(0,1),Move(0,2)), listOf(Move(1,0),Move(1,1),Move(1,2)),
            listOf(Move(2,0),Move(2,1),Move(2,2)), listOf(Move(0,0),Move(1,0),Move(2,0)),
            listOf(Move(0,1),Move(1,1),Move(2,1)), listOf(Move(0,2),Move(1,2),Move(2,2)),
            listOf(Move(0,0),Move(1,1),Move(2,2)), listOf(Move(0,2),Move(1,1),Move(2,0)),
        )
        for (line in lines) {
            val marks = line.map { board[it] }
            if (marks[0] != null && marks.all { it == marks[0] }) return marks[0]
        }
        return null
    }
    private fun isOver(board: Map<Move, Mark>) = winner(board) != null || board.size == 9
```

Play loop per peer (X = alice = first to move, O = bob):
- Scripted move lists: `X = [Move(0,0), Move(0,1), Move(0,2)]`, `O = [Move(1,0), Move(1,1)]`.
- Each peer launches a coroutine that collects `committed`, rebuilds `board` (move i → mark X if i even else O), and after each commit, if `!isOver(board)` and it is *this peer's* turn (turn = even count ⇒ X/alice, odd ⇒ O/bob), proposes its next scripted move via its `TurnSequencer`. Kick off by having alice propose her first move once a leader exists.
- Both peers stop collecting once `isOver(board)`.

Assertions:
```kotlin
        assertEquals(Mark.X, winner(finalBoardAlice))
        assertEquals(finalBoardAlice, finalBoardBob)             // same final board
        assertEquals(committedMovesAlice, committedMovesBob)     // same sequence
        assertEquals(aliceChat.state.value.toList(), bobChat.state.value.toList())
```

Keep determinism: `runTest(StandardTestDispatcher(), timeout = …)` with the raft config's `expectVirtualTime = true` and seeded RNG, mirroring the existing `TicTacToeChatTest`.

- [ ] **Step 2: Run to verify it fails (before forwarding it could not work; now it should after Tasks 2–6)**

Run: `./gradlew :examples:jvmTest --tests "*TicTacToeChat*"`
Expected: initially FAIL while you iterate the play loop; PASS once both peers converge and X wins.

- [ ] **Step 3: Run the example module**

Run: `./gradlew :examples:jvmTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add examples/src/test/kotlin/us/tractat/kuilt/examples/ examples/build.gradle.kts
git commit -m "test(examples): two-player tic-tac-toe + chat played to a win via forwarding"
```

---

## Task 8: Full build, lint, and docs sweep

**Files:**
- Check: `kuilt-raft/module.md` and any KDoc `@sample` mentioning leader-only propose
- Check: `Writerside/topics/*` and `docs/` for "only the leader can propose" / "NotYourTurnException" prose

- [ ] **Step 1: Grep for now-stale claims**

Run: `grep -rn "only the leader can propose\|NotYourTurnException\|not the current turn leader" --include=*.md --include=*.kt .`
Update any prose/KDoc to "any peer can propose (forwarded to the leader)".

- [ ] **Step 2: Full build + lint**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build detektAll`
Expected: BUILD SUCCESSFUL, zero detekt findings. (`jvmTest` alone hides Android-variant + cross-module failures — run the full `build`.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs(raft): drop leader-only-propose claims now that forwarding exists"
```

---

## Self-review notes (carried from spec)

- **Spec coverage:** Forward/ForwardResponse (T1); known-leader forward (T2); cancellable wait (T3); cancellation cleanup + self-election (T4); propose docs + NotYourTurnException removal (T5/T6); example with win/draw (T7); no-dup guarantee is preserved because nothing auto-retries (T2–T4 never re-send a sent forward).
- **Deferred (not in this plan):** §8 client-serial exactly-once dedup; the construction-boilerplate facade (#480, narrowed). A forward to a leader that dies mid-flight relies on the caller's `withTimeout`/cancellation to unblock (documented in the propose KDoc) — acceptable for v1.
- **Type consistency:** `Forward(clientRequestId, command)`, `ForwardResponse(clientRequestId, outcome)`, `ForwardOutcome.{Committed(index,term),NotLeader,Failed}`, `forwardedProposals: Map<Long, Pair<CompletableDeferred<LogEntry>, ByteArray>>`, `waitingForLeader: MutableList<Long>`, `flushWaitingForLeader()` — names used identically across T1–T4.
- **Open risk for the implementer:** confirm `becomeLeader()` (L613) and election paths funnel through the actor `cmd` channel so the per-command `flushWaitingForLeader()` (T3 S4) actually runs after a leader is established — it does, because role changes happen inside `onMessage`/`onElectionTimeout`, which are actor commands.
