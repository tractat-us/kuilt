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
import us.tractat.kuilt.test.drainAntiEntropy
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Throwaway measurement harness for #2044 Task 6 — metered warp egress before/after the
 * delta migration. Not a regression net; deleted once the numbers are recorded.
 */
private class T2044fMeterSeam(private val delegate: Seam) : Seam {
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

private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.drain(rounds: Int = 4) =
    drainAntiEntropy(METER_QUILTER_CONFIG.antiEntropyInterval, rounds = rounds, settleWindow = 0.milliseconds)

class T2044fEgressMeterTest {

    @Test
    fun meterOneTaskLifecycle() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        for (baseline in listOf(0, 100, 200)) {
            val loom = InMemoryLoom()
            val rawA = loom.host(Pattern("meter-$baseline"))
            val rawB = loom.join(InMemoryTag("meter-b-$baseline"))
            val seamA = T2044fMeterSeam(rawA)
            val seamB = T2044fMeterSeam(rawB)

            val registryA = OpRegistry().also { it.register(OpId("echo"), Op { args -> args }) }
            val registryB = OpRegistry()

            val nodeA = WarpNode(
                selfId = seamA.selfId,
                seam = seamA,
                rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = METER_QUILTER_CONFIG,
                clock = schedulerClock(testScheduler),
                strategy = ClaimStrategy.Ring,
                registry = registryA,
                epoch = 0L,
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId,
                seam = seamB,
                rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = METER_QUILTER_CONFIG,
                clock = schedulerClock(testScheduler),
                strategy = ClaimStrategy.Ring,
                registry = registryB,
                epoch = 0L,
            )

            // Baseline: `baseline` tasks pinned to B, whose registry has no ops, so they stay
            // resident in the queue map forever (stand-by) and never execute.
            repeat(baseline) { i ->
                nodeB.enqueueLocal(TaskId("bg-$i"), TaskDescriptor(op = OpId("nope"), args = ByteArray(0)))
            }
            drain(rounds = 6)

            // Measure: one task pinned to A, run end to end (enqueue → execute → result → removal).
            seamA.bytes.set(0)
            seamB.bytes.set(0)
            nodeA.enqueueLocal(TaskId("measured"), TaskDescriptor(op = OpId("echo"), args = "x".encodeToByteArray()))
            drain(rounds = 6)

            val done = nodeA.results[TaskId("measured")] != null
            println(
                "T2044F baseline=$baseline egressA=${seamA.bytes.get()} egressB=${seamB.bytes.get()} " +
                    "total=${seamA.bytes.get() + seamB.bytes.get()} completed=$done"
            )

            nodeA.close()
            nodeB.close()
        }
    }
}
