@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * What one warp task costs on the wire, and the property that cost must keep: **it does not
 * depend on how much other work the node is holding.**
 *
 * [WarpNode] replicates its queue, its results board and its intent register through
 * [us.tractat.kuilt.quilter.Quilter]s, and a replicator broadcasts a patch's delta verbatim.
 * Handing it a patch built from the whole new map therefore ships that whole map on every write
 * — so a node with a thousand tasks pending paid a thousand tasks' worth of bytes to enqueue
 * the thousand-and-first. `ORMap.put` now returns the delta itself, so every mutation site goes
 * through `quilter.mutate { it.put(…) }` (part of #2044), whose frame carries only the touched
 * entry.
 *
 * Measured here — bytes node A hands to [Seam.broadcast] across one task's whole lifecycle
 * (enqueue → execute → result → queue and intent removal), with `resident` other tasks parked
 * in the queue:
 *
 * | resident tasks | whole-state patches | delta mutators |
 * |---|---|---|
 * | 0    |  1,864 b | 1,864 b |
 * | 100  | 24,822 b | 2,116 b |
 * | 200  | 47,822 b | 2,116 b |
 * | 400  | (OOMs)   | 2,122 b |
 * | 1600 | (OOMs)   | 2,122 b |
 *
 * The assertion is **flatness**, not "cheaper than it was": "cheaper" would still pass a change
 * that reintroduced a per-resident-task term with a smaller constant, which is the thing that
 * would quietly come back.
 *
 * It has teeth — reverting the single [WarpNode.enqueue] site to `apply(Patch(state.value.put(…)))`
 * and leaving every other site on the delta path reds it (8,469 b at 50 resident tasks against
 * 27,769 b at 200 — a 3.28× ratio where the migrated code is 1.00×). The
 * measured sizes stop at 200 resident tasks deliberately: a whole-state run at 400 exhausts the
 * test JVM's heap and dies, which the results XML records as a *skip*, not a failure — a mutation
 * verdict that reads green. Below the cliff the failure is an assertion with both numbers in it.
 */
private class MeteredSeam(private val delegate: Seam) : Seam {
    val bytes = AtomicLong(0)

    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val incoming: Flow<Swatch> get() = delegate.incoming

    override suspend fun broadcast(payload: ByteArray) {
        bytes.addAndGet(payload.size.toLong())
        delegate.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        bytes.addAndGet(payload.size.toLong())
        delegate.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason) = delegate.close(reason)
}

private val METER_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/** Reads virtual time from [scheduler] so liveness timers share the test's timeline. */
private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/**
 * Bounded virtual-time drain — six anti-entropy intervals, no settle window ([ClaimStrategy.Ring]
 * has none). Never `advanceUntilIdle`: the anti-entropy loop re-arms forever.
 */
private fun TestScope.drainRounds() =
    drainAntiEntropy(METER_QUILTER_CONFIG.antiEntropyInterval, rounds = 6, settleWindow = 0.milliseconds)

/** One task's lifecycle egress, and whether it actually completed. */
private data class Lifecycle(val egress: Long, val completed: Boolean)

class WarpEgressFlatnessTest {

    /**
     * One task's end-to-end egress must not grow with the number of tasks already resident on the
     * queue. 1.2× headroom over a 4× larger queue leaves room for the few extra bytes a wider
     * sequence number and a longer task id cost, and nothing like room for a per-entry term.
     */
    @Test
    fun oneTasksEgressIsFlatInQueueSize() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val small = measureOneTaskLifecycle(resident = 50)
        val large = measureOneTaskLifecycle(resident = 200)

        assertAll(
            { assertTrue(small.completed, "the measured task never completed at 50 resident tasks") },
            { assertTrue(large.completed, "the measured task never completed at 200 resident tasks") },
            {
                assertTrue(
                    large.egress <= small.egress * 12 / 10,
                    "one task's egress grew with queue size: ${small.egress} b at 50 resident tasks, " +
                        "${large.egress} b at 200 — a whole-state patch is back on a WarpNode mutation site " +
                        "(part of #2044)",
                )
            },
        )
    }

    /**
     * Bytes node A broadcasts across one task's whole lifecycle while [resident] other tasks sit
     * in the queue.
     *
     * The resident tasks are pinned to node B, whose registry holds no ops, so they stand by
     * forever: they stay in the replicated queue map without executing or generating traffic of
     * their own. The measured task is pinned to A, whose registry does hold the op, so A alone
     * runs it and the counter is reset immediately before it is enqueued.
     */
    private suspend fun TestScope.measureOneTaskLifecycle(resident: Int): Lifecycle {
        val loom = InMemoryLoom()
        val seamA = MeteredSeam(loom.host(Pattern("warp-egress-$resident")))
        val seamB = MeteredSeam(loom.join(InMemoryTag("warp-egress-b-$resident")))

        val nodeA = meterNode(seamA, OpRegistry().also { it.register(ECHO, Op { args -> args }) })
        val nodeB = meterNode(seamB, OpRegistry())
        try {
            repeat(resident) { i ->
                nodeB.enqueueLocal(TaskId("resident-$i"), TaskDescriptor(op = OpId("unregistered"), args = ByteArray(0)))
            }
            drainRounds()

            seamA.bytes.set(0)
            nodeA.enqueueLocal(TaskId(MEASURED), TaskDescriptor(op = ECHO, args = "x".encodeToByteArray()))
            drainRounds()

            return Lifecycle(egress = seamA.bytes.get(), completed = nodeA.results[TaskId(MEASURED)] != null)
        } finally {
            nodeA.close()
            nodeB.close()
        }
    }

    private fun TestScope.meterNode(seam: Seam, registry: OpRegistry): WarpNode = WarpNode(
        selfId = seam.selfId,
        seam = seam,
        rosterFlow = seam.rosterSnapshot(),
        scope = backgroundScope,
        quilterConfig = METER_QUILTER_CONFIG,
        clock = schedulerClock(testScheduler),
        strategy = ClaimStrategy.Ring,
        registry = registry,
        epoch = 0L,
    )

    private companion object {
        val ECHO = OpId("echo")
        const val MEASURED = "measured"
    }
}
