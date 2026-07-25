/**
 * Acceptance tests for **location eligibility & affinity** (H8, Model A, design §14.6): a task
 * carrying an [Affinity] is placed only on peers whose advertised [CapSet] satisfies it, by
 * consistent-hashing over the **eligible subset** of the roster.
 *
 * Same virtual-time harness as [WarpNodePinnedExecutionTest]: [StandardTestDispatcher] with a
 * bounded [drainAntiEntropy] drain (never `advanceUntilIdle`, which would spin the Quilter's
 * re-arming anti-entropy loop), clocks read from `testScheduler` so the settle window and the
 * capability TTL sit on the same virtual timeline as `delay()`.
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val ELIG_OP = OpId("echo")

private val ELIG_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun eligClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.drainElig() =
    drainAntiEntropy(
        ELIG_QUILTER_CONFIG.antiEntropyInterval,
        rounds = 5,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
    )

private fun trackingRegistry(peer: PeerId, executedBy: MutableMap<TaskId, MutableList<PeerId>>, lock: ReentrantLock): OpRegistry =
    OpRegistry().also { r ->
        r.register(ELIG_OP, Op { args ->
            lock.withLock { executedBy.getOrPut(TaskId(args.decodeToString())) { mutableListOf() }.add(peer) }
            args
        })
    }

private fun TaskId.echoDescriptor(affinity: Affinity = Affinity.Anywhere): TaskDescriptor =
    TaskDescriptor(op = ELIG_OP, args = value.encodeToByteArray(), affinity = affinity)

class WarpNodeEligibilityTest {

    private val GPU = "GPU"
    private val gpuUsEast = CapSet(tokens = setOf(GPU), attributes = mapOf("region" to "us-east"))
    private val cpuUsWest = CapSet(tokens = setOf("CPU"), attributes = mapOf("region" to "us-west"))
    private val gpuAffinity = Affinity.has(GPU) and Affinity.attr("region", "us-east")

    /**
     * **Acceptance 1 — the eligible set is bit-identical across peers** from the same convergent
     * capability view. A,C advertise a GPU/us-east slot; B advertises CPU/us-west. After the
     * capability board converges every node computes the same eligible subset `{A, C}` for a
     * GPU/us-east affinity.
     */
    @Test
    fun eligibleSetBitIdenticalAcrossPeers() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("elig-identical"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val executedBy = mutableMapOf<TaskId, MutableList<PeerId>>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock), epoch = 0L)
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock), epoch = 0L)
        val nodeC = WarpNode(seamC.selfId, seamC, seamC.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamC.selfId, executedBy, lock), epoch = 0L)

        drainElig()

        nodeA.advertiseCapabilities(gpuUsEast)
        nodeB.advertiseCapabilities(cpuUsWest)
        nodeC.advertiseCapabilities(gpuUsEast)
        drainElig()

        val expected = setOf(seamA.selfId, seamC.selfId)
        assertAll(
            { assertEquals(expected, nodeA.eligiblePeers(gpuAffinity), "A computes {A,C}") },
            { assertEquals(expected, nodeB.eligiblePeers(gpuAffinity), "B computes {A,C}") },
            { assertEquals(expected, nodeC.eligiblePeers(gpuAffinity), "C computes {A,C}") },
            // The full converged capability view is identical on every peer.
            { assertEquals(nodeA.capabilityView(), nodeB.capabilityView(), "A/B views converged") },
            { assertEquals(nodeB.capabilityView(), nodeC.capabilityView(), "B/C views converged") },
        )

        nodeA.close(); nodeB.close(); nodeC.close()
    }

    /**
     * **Acceptance 2 — no landing on an ineligible peer under a fresh (converged) view.** A GPU
     * task lands on exactly the eligible-subset ring owner and is executed exactly once; the
     * ineligible peer (B) never runs it. The chosen peer equals the independent ring-over-`{A,C}`
     * computation, proving placement hashes over the eligible subset — not the whole roster.
     */
    @Test
    fun noLandingOnIneligiblePeer() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("elig-no-ineligible"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val executedBy = mutableMapOf<TaskId, MutableList<PeerId>>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock), epoch = 0L)
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock), epoch = 0L)
        val nodeC = WarpNode(seamC.selfId, seamC, seamC.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamC.selfId, executedBy, lock), epoch = 0L)

        drainElig()
        nodeA.advertiseCapabilities(gpuUsEast)
        nodeB.advertiseCapabilities(cpuUsWest)
        nodeC.advertiseCapabilities(gpuUsEast)
        drainElig()

        // Pick a task whose owner over the *full* roster is the INELIGIBLE peer B, yet whose owner
        // over the eligible subset {A,C} is an eligible peer. This makes the test revert-sensitive:
        // pre-H8 placement (ring over the whole roster) would land it on B and fail.
        val eligible = setOf(seamA.selfId, seamC.selfId)
        val ring = TaskRing(setOf(seamA.selfId, seamB.selfId, seamC.selfId))
        val task = generateSequence(1) { it + 1 }
            .map { TaskId("gpu-$it") }
            .first { ring.owner(it) == seamB.selfId && ring.owner(it, eligible) in eligible }
        val expectedOwner = ring.owner(task, eligible)

        nodeA.enqueue(task, task.echoDescriptor(gpuAffinity))
        drainElig()

        val runs: List<PeerId> = lock.withLock { executedBy[task]?.toList() }.orEmpty()
        assertAll(
            { assertEquals(listOf(expectedOwner), runs, "task runs once, on the eligible-subset owner") },
            { assertTrue(seamB.selfId !in runs, "ineligible B (the full-roster owner) never runs it") },
            { assertEquals(seamB.selfId, ring.owner(task), "precondition: B is the pre-H8 full-roster owner") },
            { assertTrue(lock.withLock { nodeA.results[task] != null }, "result converged to A's board") },
            { assertTrue(lock.withLock { nodeC.results[task] != null }, "result converged to C's board") },
        )

        nodeA.close(); nodeB.close(); nodeC.close()
    }

    /**
     * **Acceptance 3 — a stale/empty view cannot misplace onto an ineligible peer, and a
     * converged view change neither re-runs nor loses a completed task** (absorbed by the
     * `Results` dedup + ring re-home, no double-apply, no lost task).
     *
     * Phase 1: nobody advertises GPU → the eligible set is empty → the task stays pending and
     * no ineligible peer runs it. Phase 2: A advertises GPU → the task homes to A and runs once.
     * Phase 3: A withdraws GPU while C advertises it (the eligible set moves A→C) → the
     * already-completed task is neither re-executed nor lost; its single result is stable.
     */
    @Test
    fun staleOrChangingViewAbsorbedByReHomeAndDedup() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("elig-rehome"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val executedBy = mutableMapOf<TaskId, MutableList<PeerId>>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock), epoch = 0L)
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock), epoch = 0L)
        val nodeC = WarpNode(seamC.selfId, seamC, seamC.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamC.selfId, executedBy, lock), epoch = 0L)

        drainElig()

        val task = TaskId("gpu-rehome-task")

        // Phase 1: no GPU anywhere → nobody eligible → task pending, nobody runs it.
        nodeA.enqueue(task, task.echoDescriptor(gpuAffinity))
        drainElig()
        val phase1Runs: List<PeerId>? = lock.withLock { executedBy[task]?.toList() }
        assertAll(
            { assertNull(phase1Runs, "no eligible peer → task must not run on anyone") },
            { assertNull(lock.withLock { nodeA.results[task] }, "no result while ineligible") },
        )

        // Phase 2: A advertises GPU → eligible {A} → task homes to A and runs exactly once.
        nodeA.advertiseCapabilities(gpuUsEast)
        drainElig()
        val phase2Runs: List<PeerId> = lock.withLock { executedBy[task]?.toList() }.orEmpty()
        assertAll(
            { assertEquals(listOf(seamA.selfId), phase2Runs, "task runs once on the newly-eligible A") },
            { assertTrue(lock.withLock { nodeA.results[task] != null }, "result recorded after A runs it") },
        )

        // Phase 3: A withdraws GPU and C advertises it — the eligible owner moves A→C. The task
        // is already done: it must not re-run (dedup) and must not be lost.
        nodeA.advertiseCapabilities(CapSet.EMPTY)
        nodeC.advertiseCapabilities(gpuUsEast)
        drainElig()
        val phase3Runs: List<PeerId> = lock.withLock { executedBy[task]?.toList() }.orEmpty()
        assertAll(
            { assertEquals(listOf(seamA.selfId), phase3Runs, "completed task neither re-runs nor moves — single execution on A") },
            { assertTrue(lock.withLock { nodeC.results[task] != null }, "result still converged (not lost) on C's board") },
        )

        nodeA.close(); nodeB.close(); nodeC.close()
    }

    /**
     * **Acceptance 5 — the no-affinity default reproduces today's placement bit-for-bit.** A batch
     * of [Affinity.Anywhere] tasks (the default) each run on exactly their plain [TaskRing] owner,
     * identical to the pre-H8 ring-assigned path — capability advertisements present but ignored.
     */
    @Test
    fun noAffinityReproducesTodaysPlacementBitForBit() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("elig-noaffinity"))
        val seamB = loom.join(InMemoryTag("b"))

        val ring = TaskRing(setOf(seamA.selfId, seamB.selfId))

        val executedBy = mutableMapOf<TaskId, MutableList<PeerId>>()
        val lock = reentrantLock()

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamA.selfId, executedBy, lock), epoch = 0L)
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, ELIG_QUILTER_CONFIG, eligClock(testScheduler), registry = trackingRegistry(seamB.selfId, executedBy, lock), epoch = 0L)

        drainElig()
        // Capabilities advertised but irrelevant to Anywhere tasks.
        nodeA.advertiseCapabilities(gpuUsEast)
        nodeB.advertiseCapabilities(cpuUsWest)
        drainElig()

        val tasks = (1..12).map { TaskId("plain-$it") }
        tasks.forEach { nodeA.enqueue(it, it.echoDescriptor()) } // default Affinity.Anywhere
        drainElig()

        tasks.forEach { task ->
            val runs: List<PeerId> = lock.withLock { executedBy[task]?.toList() }.orEmpty()
            assertEquals(listOfNotNull(ring.owner(task)), runs, "Anywhere $task runs on its plain ring owner, exactly once")
        }
        assertEquals(tasks.size, lock.withLock { executedBy.size }, "all default tasks executed")

        nodeA.close(); nodeB.close()
    }
}
