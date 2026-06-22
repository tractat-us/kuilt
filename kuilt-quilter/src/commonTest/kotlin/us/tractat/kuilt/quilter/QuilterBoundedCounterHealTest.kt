/**
 * Anti-entropy heals a dropped [BoundedCounter] targeted-borrow transfer (#643,
 * Phase 1 of partial-mesh gossip #654).
 *
 * BoundedCounter's targeted borrow relies on the transfer *delta* arriving. The
 * review of the gossip design worried a dropped transfer-delta would be a silent
 * deny. Under the delta + anti-entropy model it instead degrades to *higher
 * latency*: the next anti-entropy round carries A's full state — which includes
 * the transfer — and the borrower's quota heals. This test pins that.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

private val BC_SER = QuiltMessage.serializer(BoundedCounter.serializer())

class QuilterBoundedCounterHealTest {

    @Test
    fun droppedTransferDeltaHealedByAntiEntropy() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("bc-heal"))
        val seamB = loom.join(InMemoryTag("b"))

        val aRep = ReplicaId(rawSeamA.selfId.value)
        val bRep = ReplicaId(seamB.selfId.value)
        val initial = BoundedCounter.init(mapOf(aRep to 20L))

        val config = QuilterConfig(
            antiEntropyInterval = 50.milliseconds,
            fullStateRetryLimit = 0, // isolate: only anti-entropy can deliver the transfer
            expectVirtualTime = true,
        )

        // Drop every delta broadcast from A — the transfer delta never reaches B via the fast path.
        val chaosA = ChaosSeam(rawSeamA, ChaosConfig(dropProbability = 1.0), backgroundScope, seed = 1L)

        val repA = Quilter(
            replica = aRep,
            seam = chaosA,
            initial = initial,
            messageSerializer = BC_SER,
            scope = backgroundScope,
            config = config,
            random = Random(3),
        )
        val repB = Quilter(
            replica = bRep,
            seam = seamB,
            initial = initial,
            messageSerializer = BC_SER,
            scope = backgroundScope,
            config = config,
        )

        testScheduler.advanceUntilIdle() // join handshake

        // A performs a targeted borrow: transfer 5 quota to B.
        val transfer = repA.state.value.transfer(aRep, bRep, 5L)
        assertNotNull(transfer, "A has quota to transfer")
        repA.apply(transfer)
        testScheduler.advanceUntilIdle() // the transfer delta is dropped

        assertEquals(
            0L,
            repB.state.value.quota(bRep),
            "transfer delta was dropped — B must not yet see the borrowed quota (the silent-deny case)",
        )

        // Anti-entropy heals it: A reconciles full state (including the transfer) into B.
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds * 2 + 1)
        testScheduler.runCurrent()

        assertEquals(
            5L,
            repB.state.value.quota(bRep),
            "the dropped transfer is healed by the next anti-entropy round (higher latency, not silent deny)",
        )
    }
}
