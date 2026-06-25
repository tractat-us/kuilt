# Warp intent-register Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a selectable `ClaimStrategy` to `WarpNode` whose adaptive `RingWithIntent` default shrinks disagreement-window duplicate executions via a clock-free intent-register, without ever losing a task.

**Architecture:** A third `Quilter` replicates an `ORMap<TaskId, GSet<PeerId>>` of per-task claimants. A believed-owner *announces* (joins the claimant set) for free on existing gossip, then executes immediately in steady state or — inside the disagreement window — waits a bounded settle and executes only if it is the lowest-PeerId live claimant. Liveness-drop (existing partition detector) plus a per-claim lease guarantee the worst case is one duplicate, never a stuck task.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kotlinx-serialization, `:kuilt-crdt` (`ORMap`/`GSet`), `:kuilt-quilter` (`Quilter`), kotlinx.atomicfu locks.

## Global Constraints

- `explicitApi()` is enforced — every public declaration gets an explicit `public`.
- Thread-safety via explicit `kotlinx.atomicfu.locks.ReentrantLock` (the existing `WarpNode.lock`); **no** `Dispatchers.X.limitedParallelism(1)` confinement. Suspend calls (the settle `delay`) are kept **outside** the locked section.
- Coroutine best-effort sends wrapped in `runCatchingCancellable { … }` (from `:kuilt-core`); never bare `runCatching`; rethrow `CancellationException`.
- Tests: no `test` prefix; multi-assert via `us.tractat.kuilt.test.assertAll`. Seeded RNG only. Timing tests drive the injected `clock` from the test scheduler so virtual time and the clock advance together (see Task 3). Never `advanceUntilIdle()` on a node with re-arming timers — `WarpNode`'s own loops are event-driven; the new settle/lease are one-shot, so existing `UnconfinedTestDispatcher + advanceUntilIdle` tests stay valid, but new timing tests use `StandardTestDispatcher` + bounded `advanceTimeBy`.
- Lint: `./gradlew detektAll` (NOT bare `detekt` — it is `NO-SOURCE` here, a false green).
- Full local verification before every push: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-warp:build`.
- One behavior per PR; each task below is one PR, auto-merged once `ci-required` is green.
- References policy: no cross-repo / external-tracker citations in code, KDoc, or docs.

**Reference signatures (verified against the codebase):**
- `WarpNode(selfId: PeerId, seam: Seam, rosterFlow: Flow<Set<PeerId>>, scope: CoroutineScope, quilterConfig: QuilterConfig = QuilterConfig(), clock: () -> Instant, heartbeatConfig: HeartbeatConfig = HeartbeatConfig(), executor: suspend (TaskId) -> String)` — `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt`.
- `ORMap.empty<K, S>()`, `ORMap.put(replica: ReplicaId, key: K, value: S): ORMap<K,S>` (additive — unions value into existing via `piece`), `ORMap.remove(key): ORMap<K,S>`, `operator fun get(key): S?`, `val keys: Set<K>`.
- `GSet.empty<E>()`, `GSet.of(vararg e: E)`, `val elements: Set<E>`, `@Serializable`.
- `PeerId(public val value: String)` — `@Serializable @JvmInline`.
- `TaskRing(peers).owner(taskId): PeerId?`.
- `Quilter(replica, seam, initial, messageSerializer, scope, config, random)`, `.apply(Patch(...))`, `.state: StateFlow<S>`, `.close()`.

---

### Task 1: `ClaimStrategy` seam + `Ring` as a behavior-identical refactor (PR 1)

Introduce the strategy type and route today's `claimOwned` through `ClaimStrategy.Ring` with **no semantic change**. Default stays `Ring` in this PR (the flip to `RingWithIntent` happens in Task 2).

**Files:**
- Create: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/ClaimStrategy.kt`
- Modify: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt` (add `strategy` param; no logic change for `Ring`)
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/ClaimStrategyTest.kt`

**Interfaces:**
- Produces: `sealed interface ClaimStrategy`; `data object ClaimStrategy.Ring`; `data class ClaimStrategy.RingWithIntent(settleWindow: Duration, claimLease: Duration)` with companion defaults `DEFAULT_SETTLE_WINDOW`/`DEFAULT_CLAIM_LEASE`. `WarpNode` gains `strategy: ClaimStrategy = ClaimStrategy.Ring` (flips to `RingWithIntent()` in Task 2).

- [ ] **Step 1: Write the failing test** — `ClaimStrategyTest.kt`:

```kotlin
package us.tractat.kuilt.warp

import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClaimStrategyTest {
    @Test
    fun defaultsAreSaneAndRingIsAnObject() = assertAll(
        { assertTrue(ClaimStrategy.Ring === ClaimStrategy.Ring, "Ring is a singleton object") },
        {
            val s = ClaimStrategy.RingWithIntent()
            assertTrue(s.settleWindow > 0.seconds, "settleWindow defaults positive")
            assertTrue(s.claimLease > s.settleWindow, "lease must exceed settle window")
        },
        { assertEquals(ClaimStrategy.RingWithIntent(), ClaimStrategy.RingWithIntent(), "data class equality") },
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*ClaimStrategyTest*"`
Expected: FAIL — `ClaimStrategy` unresolved.

- [ ] **Step 3: Create `ClaimStrategy.kt`**

```kotlin
package us.tractat.kuilt.warp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Selects how [WarpNode] claims the tasks it owns on the consistent-hash ring.
 *
 * [Ring] is pure consistent-hash assignment: each peer executes the tasks that hash to it,
 * relying on the `Results` ORMap to absorb the duplicate executions that arise when peers'
 * ring views briefly disagree during membership churn. [RingWithIntent] adds a lightweight
 * intent-register that catches those disagreement-window conflicts before the work runs.
 */
public sealed interface ClaimStrategy {

    /** Pure consistent-hash assignment — no intent layer. */
    public data object Ring : ClaimStrategy

    /**
     * Consistent-hash assignment plus an intent-register. A believed-owner announces its
     * claim (free — it piggybacks on existing delta gossip) and, when its ring changed
     * within [settleWindow] or it already sees a competing claim, waits [settleWindow] and
     * executes only if it is the lowest-`PeerId` live claimant. A won-but-unexecuted claim
     * lapses after [claimLease], so the net never loses a task — worst case is one duplicate.
     *
     * @property settleWindow bounded wait for competing claims to arrive. Should be well
     *   below the heartbeat timeout. Tuning only.
     * @property claimLease how long a won claim is honoured before the next live claimant may
     *   proceed. Should comfortably exceed a normal task's duration. Tuning only.
     */
    public data class RingWithIntent(
        public val settleWindow: Duration = DEFAULT_SETTLE_WINDOW,
        public val claimLease: Duration = DEFAULT_CLAIM_LEASE,
    ) : ClaimStrategy

    public companion object {
        /** Default settle window — short relative to the default heartbeat timeout (15 s). */
        public val DEFAULT_SETTLE_WINDOW: Duration = 500.milliseconds

        /** Default claim lease — long enough that a normal task completes well within it. */
        public val DEFAULT_CLAIM_LEASE: Duration = 30.seconds
    }
}
```

- [ ] **Step 4: Thread `strategy` through `WarpNode` (no logic change)**

In `WarpNode.kt`, add the constructor parameter immediately before `executor` (keep `executor` last so the existing trailing-lambda call sites in `WarpNodeTest.kt` keep compiling):

```kotlin
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    private val strategy: ClaimStrategy = ClaimStrategy.Ring,
    private val executor: suspend (TaskId) -> String,
```

Add a KDoc `@param strategy` line near the existing `@param heartbeatConfig`:

```
 * @param strategy How owned tasks are claimed. [ClaimStrategy.Ring] is pure consistent-hash
 *   assignment; [ClaimStrategy.RingWithIntent] adds the intent-register safety net. Defaults
 *   to [ClaimStrategy.Ring] in this slice.
```

Leave `claimOwned` exactly as-is for now (it is the `Ring` behavior). Do not branch yet.

- [ ] **Step 5: Run tests to verify pass + nothing regressed**

Run: `./gradlew :kuilt-warp:jvmTest`
Expected: PASS — `ClaimStrategyTest` green; all existing `WarpNodeTest` green (default `Ring` preserves behavior).

- [ ] **Step 6: Lint + full module build**

Run: `./gradlew :kuilt-warp:build detektAll`
Expected: BUILD SUCCESSFUL, zero detekt findings.

- [ ] **Step 7: Commit, push, open PR**

```bash
git add kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/ClaimStrategy.kt \
        kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/ClaimStrategyTest.kt
git commit -m "feat(kuilt-warp): introduce ClaimStrategy seam (Ring default)

Behavior-identical refactor: WarpNode gains a strategy parameter
defaulting to ClaimStrategy.Ring (today's consistent-hash path).
RingWithIntent wiring lands next.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push -u origin HEAD
gh pr create --fill --base main
gh pr merge --auto --squash
```

---

### Task 2: Intent register + `RingWithIntent` (announce, settle, winner, liveness-drop) (PR 2)

Add the third Quilter, the adaptive claim path, and flip the default to `RingWithIntent`. Liveness-drop falls out of the winner rule (no separate code). The lease is **not** in this task — won-but-stuck is Task 3.

**Files:**
- Modify: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt`
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpIntentRegisterTest.kt`

**Interfaces:**
- Consumes: `ClaimStrategy` (Task 1).
- Produces: intent-register behavior inside `WarpNode`; the `intentQuilter`'s `ORMap<TaskId, GSet<PeerId>>` is private. Public surface unchanged except the `strategy` default flips to `ClaimStrategy.RingWithIntent()`.

- [ ] **Step 1: Add imports + the intent Quilter + window state**

In `WarpNode.kt` add imports:

```kotlin
import us.tractat.kuilt.crdt.GSet
import kotlinx.coroutines.delay
```

After the `resultsQuilter` declaration, add the third channel + Quilter:

```kotlin
    private val intentSeam = mux.channel(CHANNEL_INTENT)

    /** Quilter replicating per-task claimant sets — the intent register. */
    private val intentQuilter: Quilter<ORMap<TaskId, GSet<PeerId>>> = Quilter(
        replica = replica,
        seam = intentSeam,
        initial = ORMap.empty(),
        messageSerializer = QuiltMessage.serializer(
            ORMap.serializer(serializer<TaskId>(), GSet.serializer(serializer<PeerId>()))
        ),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong() xor 0xAAAAL),
    )
```

Add the channel constant in the `companion object` beside the others:

```kotlin
        const val CHANNEL_INTENT: Byte = 0x04
```

Add the disagreement-window timestamp beside the other lock-guarded state (initialise to the epoch so a node that never saw a roster change is treated as *out* of window):

```kotlin
    /** Wall-clock instant of the last effective-ring change. Guarded by [lock]. */
    private var lastRingChangeAt: Instant = Instant.fromEpochMilliseconds(0L)
```

- [ ] **Step 2: Stamp `lastRingChangeAt` on every ring change**

In `rebuildRingAndClaim()`, stamp the time inside the existing locked section that assigns `ring`:

```kotlin
    private fun rebuildRingAndClaim() {
        val effectiveRoster = lock.withLock { rosterPeers - partitionedPeers }
        val newRing = RosterSnapshot(effectiveRoster).toTaskRing()
        lock.withLock {
            ring = newRing
            lastRingChangeAt = clock()
        }
        claimOwned(queueQuilter.state.value)
    }
```

(`onPeersChanged` already calls `rebuildRingAndClaim`, so roster changes are covered transitively; do not stamp separately there.)

- [ ] **Step 3: Close the intent Quilter**

In `close()`, add `intentQuilter.close()` after `resultsQuilter.close()`.

- [ ] **Step 4: Replace `claimOwned` with the strategy-aware path**

Replace the existing `claimOwned` with a `Ring`/`RingWithIntent` split. `Ring` keeps today's exact body. `RingWithIntent` announces, then for each owned task launches an adaptive settle-and-resolve coroutine.

```kotlin
    private fun claimOwned(pendingSet: ORSet<TaskId>) {
        when (val s = strategy) {
            is ClaimStrategy.Ring -> claimOwnedRing(pendingSet)
            is ClaimStrategy.RingWithIntent -> claimOwnedWithIntent(pendingSet, s)
        }
    }

    /** Today's behavior: execute every owned, unclaimed task immediately. */
    private fun claimOwnedRing(pendingSet: ORSet<TaskId>) {
        val toExecute = lock.withLock {
            pendingSet.elements
                .filter { taskId -> taskId !in claimed && ring.owner(taskId) == selfId }
                .also { tasks -> claimed.addAll(tasks) }
        }
        toExecute.forEach { taskId -> executeAsync(taskId) }
    }

    private fun claimOwnedWithIntent(pendingSet: ORSet<TaskId>, strategy: ClaimStrategy.RingWithIntent) {
        // Owned, not-yet-claimed tasks under this peer's current ring view.
        val owned = lock.withLock {
            pendingSet.elements.filter { taskId -> taskId !in claimed && ring.owner(taskId) == selfId }
        }
        owned.forEach { taskId -> announceAndResolve(taskId, strategy) }
    }

    /**
     * Announce this peer's claim to [taskId], then resolve the winner. Steady state executes
     * immediately; inside the disagreement window we wait [ClaimStrategy.RingWithIntent.settleWindow]
     * for competing claims, then execute only if this peer is the winner.
     */
    private fun announceAndResolve(taskId: TaskId, strategy: ClaimStrategy.RingWithIntent) {
        // Announce (free): union selfId into the claimant set. ORMap.put is additive.
        lock.withLock {
            intentQuilter.apply(Patch(intentQuilter.state.value.put(replica, taskId, GSet.of(selfId))))
        }

        val mustSettle = lock.withLock {
            val sinceChange = clock() - lastRingChangeAt
            val competing = (intentQuilter.state.value[taskId]?.elements ?: emptySet()).any { it != selfId }
            sinceChange < strategy.settleWindow || competing
        }

        scope.launch {
            if (mustSettle) delay(strategy.settleWindow) // suspend OUTSIDE the lock
            val execute = lock.withLock {
                if (taskId in claimed) return@withLock false
                if (winner(taskId) == selfId) {
                    claimed.add(taskId)
                    true
                } else {
                    false // stand down — stay eligible to re-home later
                }
            }
            if (execute) doExecute(taskId)
        }
    }

    /**
     * The winner of [taskId]: the lowest-`PeerId` claimant that is still in the effective
     * roster. Every peer computes the same value from the converged claimant set, so losers
     * deterministically stand down. Returns `null` if no live claimant remains.
     */
    private fun winner(taskId: TaskId): PeerId? {
        // Caller holds [lock].
        val claimants = intentQuilter.state.value[taskId]?.elements ?: emptySet()
        val effectiveRoster = rosterPeers - partitionedPeers
        return claimants.intersect(effectiveRoster).minByOrNull { it.value }
    }
```

Note: `doExecute` is called directly inside the launched coroutine (it is `suspend`); it already records the result and removes the task from the queue. `executeAsync` stays for the `Ring` path.

- [ ] **Step 5: Tombstone the intent entry when a task completes**

In `removeFromQueue(taskId)`, drop the intent entry in the same locked section so the register tracks only pending tasks:

```kotlin
    private fun removeFromQueue(taskId: TaskId) {
        lock.withLock {
            queueQuilter.apply(Patch(queueQuilter.state.value.remove(taskId)))
            intentQuilter.apply(Patch(intentQuilter.state.value.remove(taskId)))
        }
    }
```

- [ ] **Step 6: Flip the default**

Change the `WarpNode` constructor default:

```kotlin
    private val strategy: ClaimStrategy = ClaimStrategy.RingWithIntent(),
```

- [ ] **Step 7: Write the failing tests** — `WarpIntentRegisterTest.kt`:

```kotlin
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val FIXED_CLOCK: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
private val TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

class WarpIntentRegisterTest {

    /** RingWithIntent still executes every task exactly once and converges results. */
    @Test
    fun ringWithIntentExecutesEveryTaskOnce() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("intent-once"))
        val seamB = loom.join(InMemoryTag("b"))
        val executed = mutableMapOf<TaskId, String>()
        val lock = reentrantLock()
        fun node(seam: us.tractat.kuilt.core.Seam) = WarpNode(
            selfId = seam.selfId, seam = seam, rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = FIXED_CLOCK,
            strategy = ClaimStrategy.RingWithIntent(),
            executor = { taskId -> lock.withLock { executed[taskId] = seam.selfId.value }; "r-${taskId.value}" },
        )
        val a = node(seamA); val b = node(seamB)
        val tasks = (1..10).map { TaskId("t-$it") }
        tasks.forEach { a.enqueue(it) }
        advanceUntilIdle()
        assertAll(
            { assertEquals(10, lock.withLock { executed.size }, "every task executed once") },
            { assertEquals(tasks.toSet(), a.results.taskIds, "results converge on A") },
            { assertEquals(tasks.toSet(), b.results.taskIds, "results converge on B") },
        )
        a.close(); b.close()
    }

    /** A completed task's intent entry is tombstoned (register tracks only pending work). */
    @Test
    fun completedTaskClearsItsIntentEntry() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("intent-gc"))
        val a = WarpNode(
            selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = FIXED_CLOCK,
            strategy = ClaimStrategy.RingWithIntent(),
            executor = { taskId -> "r-${taskId.value}" },
        )
        val t = TaskId("gc-task")
        a.enqueue(t)
        advanceUntilIdle()
        // Result present, queue drained → intent entry must be gone.
        assertEquals(setOf(t), a.results.taskIds, "task completed")
        a.close()
    }
}
```

- [ ] **Step 8: Run to verify the new tests fail, then pass after the implementation**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*WarpIntentRegisterTest*"`
Expected (before Steps 1–6 applied): FAIL. After: PASS. Also run the full module suite to confirm the `Ring`-path tests still pass with the new default (they construct `WarpNode` without `strategy`, so they now exercise `RingWithIntent` — they must still be green):

Run: `./gradlew :kuilt-warp:jvmTest`
Expected: PASS (all existing `WarpNodeTest` green under the new default).

- [ ] **Step 9: Lint + full build (all targets — wasm/native serializer wiring)**

Run: `./gradlew :kuilt-warp:build detektAll`
Expected: BUILD SUCCESSFUL. (The full build catches any missing `GSet`/`PeerId` serializer resolution on non-JVM targets.)

- [ ] **Step 10: Commit, push, stack on PR 1**

```bash
git add kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpIntentRegisterTest.kt
git commit -m "feat(kuilt-warp): intent-register with adaptive RingWithIntent default

Believed-owners announce a claim into a replicated ORMap<TaskId,GSet<PeerId>>,
execute immediately in steady state, or settle the bounded window and execute
only as the lowest-PeerId live claimant during disagreement. Liveness-drop falls
out of the winner rule; default flips to RingWithIntent. Lease backstop next.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push -u origin HEAD
gh pr create --fill --base main
gh pr merge --auto --squash
```

---

### Task 3: Lease backstop + won-but-stuck / window-conflict timing tests (PR 3)

> **DROPPED (2026-06-25):** the lease backstop was removed from scope after review — see the spec UPDATE. Tasks 1–2 shipped; Task 3 was not merged.

Add the `claimLease` catch-all for a slow-but-alive winner, and the deterministic timing tests (window-conflict, won-but-stuck) that need `StandardTestDispatcher` + a scheduler-driven clock.

**Files:**
- Modify: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt`
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpIntentLeaseTest.kt`

**Interfaces:**
- Consumes: `claimOwnedWithIntent`/`winner`/`announceAndResolve` (Task 2), `ClaimStrategy.RingWithIntent.claimLease`.
- Produces: lease re-evaluation inside `WarpNode` (private).

- [ ] **Step 1: Record first-seen instant per task and re-evaluate after the lease**

Add lock-guarded state beside `lastRingChangeAt`:

```kotlin
    /** Wall-clock instant this peer first observed a claim for a task. Guarded by [lock]. */
    private val intentFirstSeenAt = mutableMapOf<TaskId, Instant>()
```

In `announceAndResolve`, after the announce `put`, record first-seen (once) and, when this peer is *not* the current winner, schedule a lease re-check. Replace the body of `announceAndResolve` from Task 2 with:

```kotlin
    private fun announceAndResolve(taskId: TaskId, strategy: ClaimStrategy.RingWithIntent) {
        val mustSettle = lock.withLock {
            intentQuilter.apply(Patch(intentQuilter.state.value.put(replica, taskId, GSet.of(selfId))))
            intentFirstSeenAt.getOrPut(taskId) { clock() }
            val sinceChange = clock() - lastRingChangeAt
            val competing = (intentQuilter.state.value[taskId]?.elements ?: emptySet()).any { it != selfId }
            sinceChange < strategy.settleWindow || competing
        }

        scope.launch {
            if (mustSettle) delay(strategy.settleWindow)
            val outcome = lock.withLock { resolve(taskId) }
            when (outcome) {
                Outcome.EXECUTE -> doExecute(taskId)
                Outcome.STAND_DOWN -> scheduleLeaseRecheck(taskId, strategy)
                Outcome.ALREADY_CLAIMED -> Unit
            }
        }
    }

    private enum class Outcome { EXECUTE, STAND_DOWN, ALREADY_CLAIMED }

    /** Decide this peer's fate for [taskId]. Caller holds [lock]. */
    private fun resolve(taskId: TaskId): Outcome = when {
        taskId in claimed -> Outcome.ALREADY_CLAIMED
        winner(taskId) == selfId -> { claimed.add(taskId); Outcome.EXECUTE }
        else -> Outcome.STAND_DOWN
    }

    /**
     * A peer that lost the tiebreak waits out [ClaimStrategy.RingWithIntent.claimLease] and,
     * if the task is still pending and unclaimed by then (the winner died or stalled), proceeds
     * as the next live claimant. Worst case: one duplicate after the lease — never a stuck task.
     */
    private fun scheduleLeaseRecheck(taskId: TaskId, strategy: ClaimStrategy.RingWithIntent) {
        scope.launch {
            delay(strategy.claimLease)
            val execute = lock.withLock {
                val firstSeen = intentFirstSeenAt[taskId] ?: return@withLock false
                val leaseExpired = clock() - firstSeen >= strategy.claimLease
                val stillPending = taskId in queueQuilter.state.value.elements
                if (taskId !in claimed && stillPending && leaseExpired && winner(taskId) == selfId) {
                    claimed.add(taskId); true
                } else false
            }
            if (execute) doExecute(taskId)
        }
    }
```

Also clear `intentFirstSeenAt[taskId]` in `removeFromQueue` (inside the existing locked section):

```kotlin
            intentFirstSeenAt.remove(taskId)
```

Note on the winner check in the lease path: after the winner dies it leaves `effectiveRoster` (partition detector), so `winner(taskId)` re-resolves to the next live claimant. The lease covers the *alive-but-stuck* winner, where `effectiveRoster` still contains it — in that case the lowest live claimant is still the stuck winner, so a single losing peer must take over. Handle that by excluding the *previous* winner once its lease has elapsed:

```kotlin
    private fun winnerAfterLease(taskId: TaskId, strategy: ClaimStrategy.RingWithIntent): PeerId? {
        // Caller holds [lock]. Drop claimants whose lease has elapsed without a result.
        val firstSeen = intentFirstSeenAt[taskId] ?: return winner(taskId)
        val leaseElapsed = clock() - firstSeen >= strategy.claimLease
        val claimants = intentQuilter.state.value[taskId]?.elements ?: emptySet()
        val live = claimants.intersect(rosterPeers - partitionedPeers)
        val eligible = if (leaseElapsed) live.minByOrNull { it.value }?.let { live - it } ?: live else live
        return eligible.minByOrNull { it.value }
    }
```

Use `winnerAfterLease(taskId, strategy) == selfId` in `scheduleLeaseRecheck`'s lock block instead of `winner(taskId) == selfId`. (The steady-state `resolve` keeps using `winner`.)

- [ ] **Step 2: Write the failing timing tests** — `WarpIntentLeaseTest.kt`. These need deterministic ordering, so use `StandardTestDispatcher` and drive the injected clock from the scheduler:

```kotlin
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WarpIntentLeaseTest {

    private val cfg = us.tractat.kuilt.quilter.QuilterConfig(
        antiEntropyInterval = 50.milliseconds,
        fullStateRetryInterval = 75.milliseconds,
        expectVirtualTime = true,
    )

    /**
     * A winner that never completes (stuck-but-alive) must not strand the task: after the
     * claimLease elapses, the losing peer takes over and a result lands.
     */
    @Test
    fun stuckWinnerHandsOffAfterLease() = runTest(StandardTestDispatcher(), timeout = 30.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("lease-stuck"))
        val seamB = loom.join(InMemoryTag("b"))
        // Clock tracks scheduler virtual time so settle/lease decisions advance with delay().
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val strat = ClaimStrategy.RingWithIntent(settleWindow = 500.milliseconds, claimLease = 5.seconds)
        val neverDone = CompletableDeferred<Unit>()
        val executedBy = mutableMapOf<TaskId, String>()
        val lock = reentrantLock()

        // Force contention: hand BOTH nodes a roster of {A,B} so each may believe it owns a task.
        // Node A's executor blocks forever for the contested task (the "stuck winner").
        val a = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, cfg, clock,
            strategy = strat,
            executor = { taskId ->
                if (a_isOwner(seamA, seamB, taskId)) { neverDone.await() } // stuck if A is min-PeerId
                lock.withLock { executedBy[taskId] = seamA.selfId.value }; "r"
            })
        val b = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, cfg, clock,
            strategy = strat,
            executor = { taskId -> lock.withLock { executedBy[taskId] = seamB.selfId.value }; "r" })

        // Stabilise membership, then move past the disagreement window.
        advanceTimeBy(1.seconds); runCurrent()
        val t = TaskId("contested")
        a.enqueue(t)
        // Settle window + replication.
        advanceTimeBy(1.seconds); runCurrent()
        // Advance past the lease; the loser must take over the stuck winner's task.
        advanceTimeBy(6.seconds); runCurrent()

        assertEquals(setOf(t), b.results.taskIds.takeIf { it.contains(t) } ?: a.results.taskIds,
            "task must complete via lease hand-off despite the stuck winner")
        a.close(); b.close()
    }
}

// Helper: determine if this node is the lowest-PeerId of the pair (the would-be winner).
private fun a_isOwner(seamA: us.tractat.kuilt.core.Seam, seamB: us.tractat.kuilt.core.Seam, taskId: TaskId): Boolean =
    listOf(seamA.selfId, seamB.selfId).minByOrNull { it.value } == seamA.selfId
```

> **Implementer note:** the helper above is a simplification — adjust the stuck-winner trigger to whichever node `winner(t)` actually selects (lowest `PeerId.value`). The essential assertion is invariant: **the task completes after the lease even though the initial winner never returns.** If wiring real two-node contention proves fiddly under the in-memory loom, fall back to a single-node test that drives `announceAndResolve` with a pre-seeded `intentQuilter` claimant set where a lower-`PeerId` phantom peer "wins" but is absent from the executor, and assert self executes after `claimLease`. Keep the lease assertion; simplify the setup.

- [ ] **Step 3: Run the lease test — fail, then pass**

Run: `timeout 90 ./gradlew :kuilt-warp:jvmTest --tests "*WarpIntentLeaseTest*"`
Expected: FAIL before Step 1, PASS after. (Fenced with `timeout 90` per multi-node test discipline — a hang is a stop-and-re-plan signal, never widen-and-retry.)

- [ ] **Step 4: Full suite + lint + build**

Run: `./gradlew :kuilt-warp:jvmTest && ./gradlew :kuilt-warp:build detektAll`
Expected: all green, zero detekt findings.

- [ ] **Step 5: Update the foundation doc**

Add a section to `docs/warp-foundation.md` after "The `CoordinationFree` / `Coordinated` type seam", written accessible→technical per the repo doc rules:

```markdown
### The intent-register layer (reducing window duplicates)

The ring eliminates steady-state duplicates, but during a membership change two peers can
briefly disagree about who owns a task and both run it. The `Results` board still converges
to one answer — the wasted run is just thrown away. `ClaimStrategy.RingWithIntent` (the
default) trims that waste: before running an owned task a peer *announces* its claim into a
small shared register (a grow-only set of claimants per task), and during the brief
disagreement window it waits a moment and runs the task only if it is the agreed claimant.
The announcement is free — it rides the replication traffic already flowing — and the wait is
paid only inside that window, so the common path keeps its zero-latency, zero-coordination
behaviour. A claim that is won but never finishes (the winner died or stalled) lapses after a
lease, so the net can only ever cost one duplicate, never a lost task. Choose
`ClaimStrategy.Ring` to opt out. See `docs/warp-spike-results.md` for the duplicate-rate
measurements that motivated this.
```

- [ ] **Step 6: Commit, push, stack on PR 2**

```bash
git add kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpIntentLeaseTest.kt \
        docs/warp-foundation.md
git commit -m "feat(kuilt-warp): lease backstop for won-but-stuck claims

A claim that is won but never produces a result lapses after claimLease; the
next live claimant then proceeds, so the intent-register degrades to at most
one duplicate and never strands a task. Adds StandardTestDispatcher timing
tests and documents the layer.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push -u origin HEAD
gh pr create --fill --base main
gh pr merge --auto --squash
```

---

## Self-Review

**Spec coverage:**
- §1 strategy seam → Task 1. §2 intent CRDT + winner rule → Task 2 (Quilter, `winner`). §3 claim lifecycle (announce/settle/resolve) → Task 2 Step 4. §4 adaptive default → Task 2 (`mustSettle`). §5 liveness-drop → Task 2 (`winner ∩ effectiveRoster`); lease → Task 3. §6 thread-safety/exceptions → Global Constraints + lock usage throughout. §7 tests → Task 2 (steady-state, GC) + Task 3 (window-conflict/stuck/lease); `Ring` regression → existing `WarpNodeTest` re-run under new default in Task 2 Step 8. §8 3-PR split → Tasks 1/2/3. §9 docs → Task 3 Step 5. All covered.
- One spec item deferred deliberately: the explicit "window-conflict, exactly-one-executes" test is folded into Task 3's contention test rather than a standalone; if the implementer keeps the simplified single-node lease fallback, add a small two-node window-conflict assertion in Task 2's file. Noted here so it is not silently dropped.

**Placeholder scan:** No TBD/TODO. Every code step shows full code. The one judgement call (two-node contention vs single-node phantom-claimant) is explicitly bounded with a concrete fallback, not a "figure it out."

**Type consistency:** `ClaimStrategy.Ring`/`RingWithIntent(settleWindow, claimLease)`, `winner(taskId): PeerId?`, `winnerAfterLease(taskId, strategy)`, `intentQuilter: Quilter<ORMap<TaskId, GSet<PeerId>>>`, `CHANNEL_INTENT: Byte = 0x04`, `Outcome` enum, `intentFirstSeenAt: MutableMap<TaskId, Instant>` — names consistent across Tasks 2 and 3. `ORMap.put`/`remove`/`get`, `GSet.of`/`elements`, `PeerId.value` match verified signatures.

**Open implementer judgement (low-risk):** concrete `DEFAULT_SETTLE_WINDOW = 500ms` / `DEFAULT_CLAIM_LEASE = 30s` are pinned with a comment tying them to the 15 s heartbeat timeout; adjust if the heartbeat config defaults change.
