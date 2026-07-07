@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Returns a clock that reads virtual time from [scheduler], keeping it in sync with `delay()` calls. */
private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/**
 * Advances virtual time in bounded steps to flush Quilter convergence and the
 * RingWithIntent settle window. Mirrors [WarpNodeTest]'s `drain()` — see that file
 * for the full rationale (short: [advanceUntilIdle] spins the Quilter anti-entropy
 * loop indefinitely; bounded steps are the safe alternative).
 */
private fun TestScope.drain() =
    drainAntiEntropy(
        TEST_QUILTER_CONFIG.antiEntropyInterval,
        rounds = 5,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
    )

private val TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private val INTENT_OP = OpId("intent-echo")

private fun intentRegistry(
    opId: OpId = INTENT_OP,
    onInvoke: (ByteArray) -> Unit = {},
): OpRegistry = OpRegistry().also { r ->
    r.register(opId, Op { args -> onInvoke(args); args })
}

private fun TaskId.intentDescriptor() = TaskDescriptor(op = INTENT_OP, args = value.encodeToByteArray())

class WarpIntentRegisterTest {

    /** RingWithIntent still executes every task exactly once and converges results. */
    @Test
    fun ringWithIntentExecutesEveryTaskOnce() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("intent-once"))
        val seamB = loom.join(InMemoryTag("b"))
        val executed = mutableMapOf<TaskId, String>()
        val lock = reentrantLock()
        val clock = schedulerClock(testScheduler)
        fun node(seam: us.tractat.kuilt.core.Seam) = WarpNode(
            selfId = seam.selfId, seam = seam, rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = clock,
            strategy = ClaimStrategy.RingWithIntent(),
            registry = intentRegistry { args ->
                lock.withLock { executed[TaskId(args.decodeToString())] = seam.selfId.value }
            },
        )
        val a = node(seamA); val b = node(seamB)
        val tasks = (1..10).map { TaskId("t-$it") }
        tasks.forEach { a.enqueue(it, it.intentDescriptor()) }
        drain()
        assertAll(
            { assertEquals(10, lock.withLock { executed.size }, "every task executed once") },
            { assertEquals(tasks.toSet(), a.results.taskIds, "results converge on A") },
            { assertEquals(tasks.toSet(), b.results.taskIds, "results converge on B") },
        )
        a.close(); b.close()
    }

    /**
     * RingWithIntent op failure must (a) not crash the node scope and (b) unclaim
     * the task so a subsequent enqueue/re-home can execute it successfully.
     *
     * Regression for the parity gap between the Ring path (executeAsync wraps doExecute
     * in runCatchingCancellable + unclaims on failure) and the RingWithIntent path
     * (announceAndResolve called doExecute bare — no recovery, task stranded in claimed).
     */
    @Test
    fun ringWithIntent_executorFailure_unclainsAndNodeRemainsAlive() = runTest(
        StandardTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("intent-failure"))
        val clock = schedulerClock(testScheduler)
        val attemptCount = atomic(0)
        val failOpId = OpId("fail-op")
        val node = WarpNode(
            selfId = seam.selfId,
            seam = seam,
            rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = clock,
            strategy = ClaimStrategy.RingWithIntent(),
            registry = OpRegistry().also { r ->
                r.register(failOpId, Op { args ->
                    val attempt = attemptCount.incrementAndGet()
                    if (attempt == 1) throw RuntimeException("simulated op failure for ${args.decodeToString()}")
                    args
                })
            },
        )
        // First enqueue — op throws; task must be unclaimed so it can be retried.
        val t = TaskId("fail-task")
        node.enqueue(t, TaskDescriptor(op = failOpId, args = t.value.encodeToByteArray()))
        drain()

        // The node must still be alive: enqueue a second task that succeeds.
        val t2 = TaskId("healthy-task")
        node.enqueue(t2, TaskDescriptor(op = failOpId, args = t2.value.encodeToByteArray()))
        drain()

        assertAll(
            { assertEquals(true, attemptCount.value >= 2, "op was invoked at least twice (fail then retry)") },
            { assertEquals(setOf(t, t2), node.results.taskIds, "both tasks completed after failure+retry") },
        )
        node.close()
    }

    /**
     * Deterministic pin of the poisoned-claim standoff behind the real-WebSocket failover
     * flake (issue #931).
     *
     * The race: [WarpNode] seeds `ring = TaskRing({selfId})` but `rosterPeers = emptySet()`,
     * and the two are only reconciled when the first `rosterFlow` emission is *processed* —
     * asynchronously, racing inbound queue deltas. A task that lands in that window is
     * claimed under the self-only ring (intent announced into the **grow-only** claimant
     * GSet), then resolved against the still-empty roster: `winner() == null`, so the node
     * stands down — leaving a permanent claim it will never re-evaluate once its ring
     * converges and assigns the task elsewhere. The true owner then announces, computes
     * `winner == the stale claimant` (lowest live PeerId) and deterministically stands down
     * too. Nobody executes; the WebSocket test burned its 30 s convergence timeout.
     *
     * The contract pinned here: an announced claim is **binding** — the winning claimant
     * must follow through and execute even when its converged ring no longer assigns it
     * the task.
     */
    @Test
    fun staleSelfRingClaim_winnerFollowsThrough_taskStillExecutes() = runTest(
        StandardTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("intent-stale-claim")) // peer-1 — the lowest PeerId
        val seamB = loom.join(InMemoryTag("b"))              // peer-2
        val a = seamA.selfId
        val b = seamB.selfId

        // A task the *converged* two-peer ring assigns to B.
        val ring = TaskRing(setOf(a, b))
        val task = generateSequence(1) { it + 1 }
            .map { TaskId("stale-claim-$it") }
            .first { ring.owner(it) == b }

        val executed = mutableListOf<PeerId>()
        val lock = reentrantLock()
        val clock = schedulerClock(testScheduler)

        // A's first roster emission is still in flight: nothing processed yet, so A's ring
        // is the seeded self-only ring while rosterPeers is still empty — the exact startup
        // window the WarpNodeWebSocketTest race hits under load.
        val lateRosterA = MutableSharedFlow<Set<PeerId>>()
        val nodeA = WarpNode(
            selfId = a, seam = seamA, rosterFlow = lateRosterA,
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = clock,
            strategy = ClaimStrategy.RingWithIntent(),
            registry = intentRegistry { lock.withLock { executed += a } },
        )
        // Lands before any roster emission: A claims under the self-only ring, announces
        // intent, resolves against the empty roster (winner == null) and stands down —
        // the claim is now poisoned into the grow-only register.
        nodeA.enqueue(task, task.intentDescriptor())
        drain()

        // B starts with the converged roster, syncs queue + intent, sees the stale claimant
        // (lower PeerId ⇒ the winner) and deterministically stands down.
        val nodeB = WarpNode(
            selfId = b, seam = seamB, rosterFlow = MutableStateFlow(setOf(a, b)),
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = clock,
            strategy = ClaimStrategy.RingWithIntent(),
            registry = intentRegistry { lock.withLock { executed += b } },
        )
        drain()

        // A's delayed roster emission finally lands: the converged ring no longer assigns
        // the task to A. Pre-fix, A dropped its stale claim on re-evaluation (non-owner)
        // while B kept standing down to it — permanent standoff, task never executed.
        lateRosterA.emit(setOf(a, b))
        drain()

        assertAll(
            {
                assertEquals(
                    listOf(a),
                    lock.withLock { executed.toList() },
                    "the winning claimant follows through exactly once",
                )
            },
            { assertEquals(setOf(task), nodeA.results.taskIds, "result converges on A") },
            { assertEquals(setOf(task), nodeB.results.taskIds, "result converges on B") },
        )
        nodeA.close()
        nodeB.close()
    }

    /** A completed task's intent entry is tombstoned (register tracks only pending work). */
    @Test
    fun completedTaskClearsItsIntentEntry() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("intent-gc"))
        val clock = schedulerClock(testScheduler)
        val a = WarpNode(
            selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = clock,
            strategy = ClaimStrategy.RingWithIntent(),
            registry = intentRegistry(),
        )
        val t = TaskId("gc-task")
        a.enqueue(t, t.intentDescriptor())
        drain()
        // Result present, queue drained → intent entry must be gone.
        assertEquals(setOf(t), a.results.taskIds, "task completed")
        a.close()
    }
}
