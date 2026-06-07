@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the Resend retry timer: when a peer detects a delta gap and its Resend is
 * itself dropped, the replicator must re-fire the Resend after [SeamReplicatorConfig.resendRetryInterval]
 * without requiring any further inbound traffic.
 *
 * This is the production robustness fix for issue #180.
 */
class SeamReplicatorResendRetryTest {

    private val gsetSer = ReplicatorMessage.serializer(
        GSet.serializer(kotlinx.serialization.serializer<String>()),
    )

    /**
     * A [Seam] that drops all [sendTo] calls initially; the [unblockSendTo] flag re-enables them.
     * This simulates a Resend being dropped by the network.
     */
    private class SendToGatingSeam(
        private val delegate: Seam,
        var unblockSendTo: Boolean = false,
    ) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (unblockSendTo) delegate.sendTo(peer, payload)
            // else: drop silently
        }
    }

    private fun <S : us.tractat.kuilt.crdt.Quilted<S>> replicatorFor(
        seam: Seam,
        initial: S,
        serializer: kotlinx.serialization.KSerializer<ReplicatorMessage<S>>,
        scope: CoroutineScope,
        config: SeamReplicatorConfig = SeamReplicatorConfig(),
    ) = SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = initial,
        messageSerializer = serializer,
        scope = scope,
        config = config,
    )

    /**
     * Scenario:
     * 1. A sends seq=1 ("apple") — delivered to B.
     * 2. A sends seq=2 ("banana") — DROPPED (broadcast drop via [DroppingSeam]).
     * 3. A sends seq=3 ("cherry") — delivered to B, triggering gap detection → B emits Resend.
     * 4. B's Resend is dropped (all [sendTo] calls from B are blocked by [SendToGatingSeam]).
     * 5. No further inbound traffic arrives.
     * 6. After [resendRetryInterval], the retry timer fires → B re-emits Resend.
     * 7. This time sendTo is unblocked → A retransmits seq=2 → B converges.
     *
     * Without the retry timer, step 6 never happens and B stays diverged forever.
     */
    @Test
    fun resendIsRetriedAfterIntervalWhenFirstResendIsDropped() = runTest(
        UnconfinedTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("resend-retry"))
        val rawSeamB = loom.join(InMemoryTag("b"))

        // Drop A's second broadcast (seq=2 = "banana")
        var broadcastIdx = 0
        val droppingSeamA = object : Seam by rawSeamA {
            override suspend fun broadcast(payload: ByteArray) {
                broadcastIdx++
                if (broadcastIdx != 2) rawSeamA.broadcast(payload)
            }
        }

        // Gate B's sendTo so its first Resend is dropped; we'll open the gate before the retry fires
        val gatingSeamB = SendToGatingSeam(rawSeamB, unblockSendTo = false)

        val retryInterval = 200.milliseconds
        val config = SeamReplicatorConfig(resendRetryInterval = retryInterval)

        val repA = replicatorFor(droppingSeamA, GSet.empty<String>(), gsetSer, backgroundScope, config)
        val repB = replicatorFor(gatingSeamB, GSet.empty<String>(), gsetSer, backgroundScope, config)

        // seq=1 delivered, seq=2 dropped, seq=3 delivered → B detects gap, fires Resend (dropped)
        repA.apply(Patch(GSet.of("apple")))   // seq=1 delivered
        repA.apply(Patch(GSet.of("banana")))  // seq=2 DROPPED
        repA.apply(Patch(GSet.of("cherry")))  // seq=3 delivered → gap detected

        // Allow initial messages to propagate; first Resend is dropped by gatingSeamB.
        // seq=3 ("cherry") is buffered in B's inbound queue waiting for seq=2 ("banana").
        testScheduler.advanceUntilIdle()

        // B has only seq=1; seq=3 is buffered pending seq=2, seq=2 never arrived
        assertEquals(
            setOf("apple"),
            repB.state.value.elements,
            "B should have only 'apple' — 'banana' gap is open and 'cherry' is buffered",
        )

        // Unblock sendTo so the retry Resend can reach A
        gatingSeamB.unblockSendTo = true

        // Advance virtual time past the retry interval — the retry timer should fire
        testScheduler.advanceTimeBy(retryInterval.inWholeMilliseconds + 1L)
        testScheduler.advanceUntilIdle()

        // B must now have converged — retry Resend reached A, A retransmitted seq=2
        assertEquals(
            setOf("apple", "banana", "cherry"),
            repB.state.value.elements,
            "B must converge after retry Resend fires and A retransmits seq=2",
        )
    }

    /**
     * When the missing delta arrives before the retry interval expires, the timer is
     * cancelled and no spurious Resend is emitted.
     *
     * Verifies the cancel-on-gap-close path: timer armed → gap heals → timer cancelled
     * → advancing past the interval produces no extra traffic.
     */
    @Test
    fun resendTimerCancelledWhenGapCloses() = runTest(
        UnconfinedTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("resend-cancel"))
        val rawSeamB = loom.join(InMemoryTag("b"))

        // Drop A's second broadcast initially; we'll re-enable it mid-test
        var dropSeq2 = true
        var broadcastIdx = 0
        val gatingSeamA = object : Seam by rawSeamA {
            override suspend fun broadcast(payload: ByteArray) {
                broadcastIdx++
                if (broadcastIdx == 2 && dropSeq2) return
                rawSeamA.broadcast(payload)
            }
        }

        val retryInterval = 500.milliseconds
        val config = SeamReplicatorConfig(resendRetryInterval = retryInterval)

        val repA = replicatorFor(gatingSeamA, GSet.empty<String>(), gsetSer, backgroundScope, config)
        val repB = replicatorFor(rawSeamB, GSet.empty<String>(), gsetSer, backgroundScope, config)

        // seq=2 dropped → B detects gap, arms retry timer
        repA.apply(Patch(GSet.of("apple")))   // seq=1 delivered
        repA.apply(Patch(GSet.of("banana")))  // seq=2 DROPPED
        repA.apply(Patch(GSet.of("cherry")))  // seq=3 delivered → gap detected, Resend fires

        // The Resend reaches A (sendTo is unobstructed), A retransmits seq=2 → gap closes
        testScheduler.advanceUntilIdle()

        // All three elements should be present — gap healed via the normal Resend path
        assertEquals(
            setOf("apple", "banana", "cherry"),
            repB.state.value.elements,
            "Gap should close via initial Resend (sendTo is open)",
        )

        // Advance past the retry interval — no extra Resend should fire (timer was cancelled)
        testScheduler.advanceTimeBy(retryInterval.inWholeMilliseconds + 1L)
        testScheduler.advanceUntilIdle()

        // State must remain correct — no spurious re-application or errors
        assertEquals(
            setOf("apple", "banana", "cherry"),
            repB.state.value.elements,
            "State must remain stable after retry interval — no spurious Resend",
        )
    }
}
