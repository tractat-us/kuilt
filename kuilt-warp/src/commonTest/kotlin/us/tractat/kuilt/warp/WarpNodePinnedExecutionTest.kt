/**
 * Tests for **pinned execution** in [WarpNode] — pinning a free-path task to a named owner,
 * decoupling who-runs from the consistent-hash ring (the foundation for data-local workloads
 * such as federated learning).
 *
 * Runs under [UnconfinedTestDispatcher] with bounded [advanceTimeBy] via [drainPinned] — never
 * [advanceUntilIdle], which would spin the Quilter's re-arming anti-entropy loop (see
 * [WarpNodeTest] for the full rationale). Clocks read virtual time from `testScheduler` so the
 * settle-window check stays on the same timeline as `delay()`.
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val PIN_OP = OpId("echo")

private val PINNED_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun pinnedClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/** Bounded virtual-time drain: five anti-entropy intervals then the settle window. */
private fun TestScope.drainPinned() {
    repeat(5) { advanceTimeBy(PINNED_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW)
    runCurrent()
}

/** A registry whose op records the executing peer keyed by the task id carried in [args]. */
private fun trackingRegistry(peer: PeerId, executedBy: MutableMap<TaskId, PeerId>, lock: ReentrantLock): OpRegistry =
    OpRegistry().also { r ->
        r.register(PIN_OP, Op { args ->
            lock.withLock { executedBy[TaskId(args.decodeToString())] = peer }
            args
        })
    }

private fun TaskId.echoDescriptor(pinnedOwner: PeerId? = null): TaskDescriptor =
    TaskDescriptor(op = PIN_OP, args = value.encodeToByteArray(), pinnedOwner = pinnedOwner)

class WarpNodePinnedExecutionTest {

    /**
     * A task pinned to a peer that is **not** its hash-ring owner runs on the pinned owner —
     * and the ring owner never runs it.
     *
     * Proof: pick a task whose unpinned [TaskRing] owner (default seed/vnode, matching what
     * [WarpNode] builds) is peer B; pin it to peer A. A executes it, B does not, and the
     * result lands on the converged board.
     */
    @Test
    fun pinnedTaskRunsOnItsOwnerNotTheRingOwner() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("pinned-owner-test"))
        val seamB = loom.join(InMemoryTag("b"))

        // A task whose *unpinned* ring owner is B — the ring WarpNode builds uses the same
        // default seed (0) and vnode count (150) as this snapshot.
        val ring = TaskRing(setOf(seamA.selfId, seamB.selfId))
        val taskOwnedByB = generateSequence(1) { it + 1 }
            .map { TaskId("ring-b-$it") }
            .first { ring.owner(it) == seamB.selfId }

        val executedBy = mutableMapOf<TaskId, PeerId>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock))
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock))

        drainPinned() // let the two-peer mesh form

        // Pin B's ring-task to A.
        nodeA.enqueue(taskOwnedByB, taskOwnedByB.echoDescriptor(pinnedOwner = seamA.selfId))
        drainPinned()

        assertAll(
            { assertEquals(seamA.selfId, lock.withLock { executedBy[taskOwnedByB] }, "pinned owner A must execute the task") },
            { assertEquals(seamB.selfId, ring.owner(taskOwnedByB), "precondition: B is the unpinned ring owner") },
            { assertEquals(1, lock.withLock { executedBy.size }, "exactly one peer executed the pinned task") },
            { assertTrue(lock.withLock { nodeA.results[taskOwnedByB] != null }, "result on A's board") },
            { assertTrue(lock.withLock { nodeB.results[taskOwnedByB] != null }, "result converged to B's board") },
        )

        nodeA.close()
        nodeB.close()
    }

    /**
     * A pinned task whose owner is absent from the roster **does not re-home** to a survivor —
     * it stays pending — and runs as soon as the owner rejoins. This is the federated-learning
     * guarantee: only the data owner ever runs its own step.
     *
     * Control: an *unpinned* task enqueued in the same window IS executed by a live peer,
     * proving the system is live and it is specifically the pin holding the pinned task back.
     */
    @Test
    fun pinnedTaskDoesNotReHomeWhenOwnerAbsentThenRunsOnReturn() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("pinned-no-rehome-test"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        // Shared roster flow — C is connected to the loom but excluded from the roster until
        // it "returns". The seam carries replication; the roster decides liveness/ownership.
        val roster = MutableStateFlow(setOf(seamA.selfId, seamB.selfId))

        val executedBy = mutableMapOf<TaskId, PeerId>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, roster, backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock))
        val nodeB = WarpNode(seamB.selfId, seamB, roster, backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock))
        val nodeC = WarpNode(seamC.selfId, seamC, roster, backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamC.selfId, executedBy, lock))

        drainPinned()

        val pinnedToC = TaskId("pinned-to-absent-c")
        val controlTask = TaskId("unpinned-control")
        nodeA.enqueue(pinnedToC, pinnedToC.echoDescriptor(pinnedOwner = seamC.selfId))
        nodeA.enqueue(controlTask, controlTask.echoDescriptor()) // unpinned — ring-assigned
        drainPinned()

        // C is absent from the roster: nobody runs the pinned task; the unpinned control runs.
        assertAll(
            { assertNull(lock.withLock { executedBy[pinnedToC] }, "pinned task must NOT re-home while owner C is absent") },
            { assertTrue(lock.withLock { executedBy[controlTask] } in setOf(seamA.selfId, seamB.selfId), "unpinned control task runs on a live peer — system is live") },
            { assertNull(lock.withLock { nodeA.results[pinnedToC] }, "no result recorded for the pending pinned task") },
        )

        // C returns to the roster — now the pinned task runs, and only on C.
        roster.value = setOf(seamA.selfId, seamB.selfId, seamC.selfId)
        drainPinned()

        assertAll(
            { assertEquals(seamC.selfId, lock.withLock { executedBy[pinnedToC] }, "pinned task runs on owner C once it returns") },
            { assertTrue(lock.withLock { nodeC.results[pinnedToC] != null }, "result recorded after C runs it") },
        )

        nodeA.close()
        nodeB.close()
        nodeC.close()
    }

    /**
     * Unpinned tasks are unaffected by the pinned-execution change: an ordinary [enqueue]
     * with no pin still ring-assigns and executes exactly as before.
     */
    @Test
    fun unpinnedTasksStillRingAssign() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("unpinned-regression-test"))
        val seamB = loom.join(InMemoryTag("b"))

        val ring = TaskRing(setOf(seamA.selfId, seamB.selfId))

        val executedBy = mutableMapOf<TaskId, PeerId>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock))
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock))

        drainPinned()

        val tasks = (1..12).map { TaskId("plain-$it") }
        tasks.forEach { nodeA.enqueue(it, it.echoDescriptor()) }
        drainPinned()

        // Every task ran on exactly its ring owner — no pinning influence.
        tasks.forEach { task ->
            assertEquals(ring.owner(task), lock.withLock { executedBy[task] }, "unpinned $task must run on its ring owner")
        }
        assertEquals(tasks.size, lock.withLock { executedBy.size }, "all unpinned tasks executed exactly once")

        nodeA.close()
        nodeB.close()
    }

    /**
     * [WarpNode.enqueueLocal] pins to self: each node's locally-enqueued task runs on that node,
     * regardless of which peer the hash ring would otherwise assign it to.
     */
    @Test
    fun enqueueLocalPinsToSelf() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("enqueue-local-test"))
        val seamB = loom.join(InMemoryTag("b"))

        val executedBy = mutableMapOf<TaskId, PeerId>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock))
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, PINNED_QUILTER_CONFIG, pinnedClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock))

        drainPinned()

        val localToA = TaskId("local-a")
        val localToB = TaskId("local-b")
        nodeA.enqueueLocal(localToA, localToA.echoDescriptor())
        nodeB.enqueueLocal(localToB, localToB.echoDescriptor())
        drainPinned()

        assertAll(
            { assertEquals(seamA.selfId, lock.withLock { executedBy[localToA] }, "A's enqueueLocal task runs on A") },
            { assertEquals(seamB.selfId, lock.withLock { executedBy[localToB] }, "B's enqueueLocal task runs on B") },
        )

        nodeA.close()
        nodeB.close()
    }
}
