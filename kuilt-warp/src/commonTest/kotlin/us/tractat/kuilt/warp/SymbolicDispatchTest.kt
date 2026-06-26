/**
 * C2 go/no-go: **symbolic dispatch** through the [OpRegistry].
 *
 * Proves the defining property of named-op dispatch: a [TaskDescriptor] enqueued on one
 * peer is claimed by its ring-owner peer, which resolves the [OpId] in its **own** local
 * [OpRegistry] and runs **its own copy** of the function — the result then merges back onto
 * every peer's board. The function never crosses the wire; only its name (and args) do.
 *
 * Mirrors the coroutine discipline of the module's [WarpNodeTest] 2-node execution test:
 * [UnconfinedTestDispatcher] with virtual time driven by bounded [advanceTimeBy] steps via
 * [settle] — never [advanceUntilIdle] (the Quilter anti-entropy timers re-arm forever).
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val DISPATCH_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun dispatchClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/**
 * Bounded virtual-time advance: step through anti-entropy intervals to converge replication,
 * then past the RingWithIntent settle window, then a few more to let results merge back.
 * Never [advanceUntilIdle] (the anti-entropy timers re-arm forever).
 */
private fun TestScope.settle() {
    repeat(6) { advanceTimeBy(DISPATCH_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW); runCurrent()
    repeat(6) { advanceTimeBy(DISPATCH_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
}

class SymbolicDispatchTest {

    /**
     * End-to-end: descriptors enqueued on A are dispatched by name to their owners, each
     * owner runs *its own* registered `reverse` op, and every result merges onto both boards.
     */
    @Test
    fun descriptorIsDispatchedByNameAndExecutedOnOwner() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("symbolic-dispatch"))
            val seamB = loom.join(InMemoryTag("b"))

            val executedBy = mutableMapOf<TaskId, PeerId>()
            val lock = reentrantLock()
            val reverseOp = OpId("reverse")

            // Each peer registers the SAME op name in its OWN registry — symbolic dispatch
            // means the owner runs its local copy, recording that it (not the enqueuer) ran it.
            fun registryFor(peerId: PeerId): OpRegistry = OpRegistry().also { r ->
                r.register(reverseOp, Op { args ->
                    lock.withLock { executedBy[TaskId(args.decodeToString())] = peerId }
                    args.reversedArray()
                })
            }

            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = DISPATCH_QUILTER_CONFIG,
                clock = dispatchClock(testScheduler), registry = registryFor(seamA.selfId),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId, seam = seamB, rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope, quilterConfig = DISPATCH_QUILTER_CONFIG,
                clock = dispatchClock(testScheduler), registry = registryFor(seamB.selfId),
            )

            val tasks = (1..6).map { TaskId("rev-$it") }
            tasks.forEach { nodeA.enqueue(it, TaskDescriptor(reverseOp, it.value.encodeToByteArray())) }
            settle()

            assertAll(
                { tasks.forEach { t -> assertNotNull(nodeA.results[t], "A missing result for $t") } },
                { tasks.forEach { t -> assertNotNull(nodeB.results[t], "B missing result for $t") } },
                {
                    tasks.forEach { t ->
                        val expected = t.value.encodeToByteArray().reversedArray().decodeToString()
                        assertEquals(expected, nodeA.results[t]!!.bytes.decodeToString(), "wrong result bytes for $t")
                    }
                },
                { assertEquals(tasks.toSet(), executedBy.keys, "every task executed exactly once, by its owner") },
            )
        }

    /**
     * The proof that **code never travels**: the owner with no matching op in its *own*
     * registry stands by ("bobbin not loaded yet"), even though the enqueuer holds the op.
     * Dispatch is resolved by the owner's local registry, never by shipping the function.
     */
    @Test
    fun ownerWithoutTheOpStandsByEvenWhenEnqueuerHasIt() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("symbolic-standby"))
            val seamB = loom.join(InMemoryTag("b"))
            val op = OpId("only-on-a")

            // Roster = {B} on both nodes ⇒ B owns every task.
            val roster = MutableStateFlow<Set<PeerId>>(setOf(seamB.selfId))

            val regA = OpRegistry().also { it.register(op, Op { args -> args }) } // A has the op…
            val regB = OpRegistry() // …B does not — the unresolved-op standby state.

            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = DISPATCH_QUILTER_CONFIG, clock = dispatchClock(testScheduler),
                strategy = ClaimStrategy.Ring, registry = regA,
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId, seam = seamB, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = DISPATCH_QUILTER_CONFIG, clock = dispatchClock(testScheduler),
                strategy = ClaimStrategy.Ring, registry = regB,
            )

            val task = TaskId("x")
            nodeA.enqueue(task, TaskDescriptor(op, task.value.encodeToByteArray()))
            settle()

            assertAll(
                { assertNull(nodeB.results[task], "owner B lacks the op — must stand by, not execute") },
                { assertNull(nodeA.results[task], "A holds the op but isn't the owner — must not execute") },
            )
        }
}
