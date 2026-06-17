@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the FullState retry timer: when a late-joining peer's initial FullState snapshot
 * is dropped, the replicator must re-send it after [QuilterConfig.fullStateRetryInterval]
 * without requiring any further inbound traffic.
 *
 * This is the same class of fix that the Resend retry (#180) applied — a unicast control
 * message without retry semantics is a silent-data-loss vector.
 */
class QuilterFullStateRetryTest {

    private val gsetSer = QuiltMessage.serializer(
        GSet.serializer(kotlinx.serialization.serializer<String>()),
    )

    /**
     * A [Seam] that drops all [sendTo] calls initially; setting [unblockSendTo] = true
     * re-enables them. Simulates the initial FullState being lost in transit.
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
        serializer: kotlinx.serialization.KSerializer<QuiltMessage<S>>,
        scope: CoroutineScope,
        config: QuilterConfig = QuilterConfig(),
    ) = Quilter(
        replica = us.tractat.kuilt.crdt.ReplicaId(seam.selfId.value),
        seam = seam,
        initial = initial,
        messageSerializer = serializer,
        scope = scope,
        config = config,
    )

    /**
     * Scenario:
     * 1. A hosts and applies a delta before B joins, so B is a genuine late joiner.
     * 2. B joins — A sends a FullState to B, but it is dropped (A's gating seam blocks sendTo).
     *    B also sends A a FullState (symmetric first-contact), but A's dispatch would cancel
     *    the retry — so we also gate B's sendTo to A so A receives nothing from B initially.
     * 3. We unblock both gates so the retry attempt can succeed.
     * 4. Advance virtual time past [fullStateRetryInterval] — retry FullState fires → B converges.
     *
     * Without the retry timer, B stays diverged forever after the initial FullState is lost.
     */
    @Test
    fun fullStateIsRetriedWhenInitialIsDropped() = runTest(
        UnconfinedTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("fullstate-retry"))

        // Gate A's sendTo so the FullState sent to the late joiner is dropped
        val gatingSeamA = SendToGatingSeam(rawSeamA, unblockSendTo = false)

        val retryInterval = 200.milliseconds
        val config = QuilterConfig(
            fullStateRetryInterval = retryInterval,
            expectVirtualTime = true,
        )

        val repA = replicatorFor(gatingSeamA, GSet.empty<String>(), gsetSer, backgroundScope, config)

        // A applies state before B joins — this is what B needs to catch up on
        repA.apply(Patch(GSet.of("apple")))
        testScheduler.advanceUntilIdle()

        // B joins late — A sees B as a new peer and fires sendFullStateTo(B), which is gated
        val rawSeamB = loom.join(InMemoryTag("b"))

        // Also gate B's sendTo so B's symmetric FullState to A doesn't trigger cancelFullStateRetry
        val gatingSeamB = SendToGatingSeam(rawSeamB, unblockSendTo = false)
        val repB = replicatorFor(gatingSeamB, GSet.empty<String>(), gsetSer, backgroundScope, config)

        // Drain: A's FullState to B is dropped; B's FullState to A is also dropped
        testScheduler.advanceUntilIdle()

        assertEquals(
            emptySet<String>(),
            repB.state.value.elements,
            "B should have no state — initial FullState was dropped",
        )

        // Unblock both gates so the retry FullState from A can reach B, and B can ack back
        gatingSeamA.unblockSendTo = true
        gatingSeamB.unblockSendTo = true

        // Advance virtual time past the retry interval — the retry timer should fire
        testScheduler.advanceTimeBy(retryInterval.inWholeMilliseconds + 1L)
        testScheduler.advanceUntilIdle()

        assertEquals(
            setOf("apple"),
            repB.state.value.elements,
            "B must converge after retry FullState fires and reaches it",
        )
    }

    /**
     * Scenario:
     * 1. A hosts, B joins — B receives the FullState (sendTo is open from the start).
     * 2. A applies a delta → B receives it via broadcast and sends an Ack back.
     * 3. Advance past [fullStateRetryInterval] — the retry timer must have been cancelled
     *    when B's first message arrived; no spurious FullState should be sent.
     *
     * Verifies the cancel-on-peer-response path: timer armed → peer sends any message →
     * timer cancelled → advancing past the interval is quiet.
     */
    @Test
    fun fullStateRetryIsCancelledWhenPeerResponds() = runTest(
        UnconfinedTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("fullstate-cancel"))
        val rawSeamB = loom.join(InMemoryTag("b"))

        val retryInterval = 500.milliseconds
        val config = QuilterConfig(
            fullStateRetryInterval = retryInterval,
            expectVirtualTime = true,
        )

        val repA = replicatorFor(rawSeamA, GSet.empty<String>(), gsetSer, backgroundScope, config)
        val repB = replicatorFor(rawSeamB, GSet.empty<String>(), gsetSer, backgroundScope, config)

        // B receives the FullState (sendTo open); A applies a delta → B acks back
        repA.apply(Patch(GSet.of("apple")))
        testScheduler.advanceUntilIdle()

        // Both peers must have converged via the normal path
        assertEquals(
            setOf("apple"),
            repB.state.value.elements,
            "B should have received the initial FullState and the delta",
        )

        // Advance past the retry interval — no spurious retry should fire
        testScheduler.advanceTimeBy(retryInterval.inWholeMilliseconds + 1L)
        testScheduler.advanceUntilIdle()

        // State must remain correct — no double-applies or errors from a spurious retry
        assertEquals(
            setOf("apple"),
            repB.state.value.elements,
            "State must remain stable after retry interval — no spurious FullState retry",
        )
    }
}
