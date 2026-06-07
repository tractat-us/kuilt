@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class SeamReplicatorEvictionTest {

    private val msgSer = ReplicatorMessage.serializer(GCounter.serializer())

    /**
     * A controllable [MonotonicMillis] for tests: call [advanceBy] to simulate
     * time passing without using the system clock.
     */
    private class FakeClock(private var now: Long = 0L) : MonotonicMillis {
        override fun now(): Long = now
        fun advanceBy(ms: Long) { now += ms }
    }

    /**
     * A [Seam] that wraps a real [InMemoryLoom]-derived seam but lets tests
     * replace its [peers] StateFlow so we can simulate a peer disappearing
     * without actually closing the underlying seam.
     */
    private class ControllableSeam(
        private val delegate: Seam,
        overridePeers: StateFlow<Set<PeerId>>,
    ) : Seam by delegate {
        override val peers: StateFlow<Set<PeerId>> = overridePeers
    }

    private fun replicatorFor(
        seam: Seam,
        scope: CoroutineScope,
        config: SeamReplicatorConfig,
        clock: MonotonicMillis,
    ) = SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = msgSer,
        scope = scope,
        config = config,
        clock = clock,
    )

    /**
     * B disappears from [Seam.peers] and goes silent; after the eviction interval
     * passes, the anti-entropy job should evict B from A's known-peer set.
     *
     * Uses a [FakeClock] to advance time deterministically without actual delays.
     */
    @Test
    fun silentAbsentPeerIsEvictedAfterTtl() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("evict-test"))

        val clock = FakeClock()
        val config = SeamReplicatorConfig(
            evictionAfter = 100.milliseconds,
            antiEntropyInterval = 50.milliseconds,
            expectVirtualTime = true,
        )

        // Give A a controllable peers flow so we can simulate B leaving
        val controlledPeers = MutableStateFlow(loom.peers.value)
        val seamA = ControllableSeam(rawSeamA, controlledPeers)

        val repA = replicatorFor(seamA, backgroundScope, config, clock)

        // Simulate B joining by injecting its PeerId into A's peers flow
        val bId = PeerId("b")
        controlledPeers.value = setOf(rawSeamA.selfId, bId)
        testScheduler.advanceUntilIdle()

        // B is now known to A
        assertTrue(repA.knownPeersForTest.contains(bId), "B should be known after joining")

        // B leaves seam.peers — it's absent but not yet stale
        controlledPeers.value = setOf(rawSeamA.selfId)
        testScheduler.advanceUntilIdle()

        // B is absent but not yet stale — must still be known (not evicted yet)
        assertTrue(
            repA.knownPeersForTest.contains(bId),
            "B must remain known until eviction TTL expires",
        )

        // Advance clock past the eviction TTL
        clock.advanceBy(150L)

        // Advance test time to trigger the anti-entropy loop
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds + 1)

        // B is now stale and absent — must be evicted
        assertFalse(
            repA.knownPeersForTest.contains(bId),
            "B must be evicted after TTL and anti-entropy tick",
        )
    }

    /**
     * An active peer (present in [Seam.peers]) should never be evicted, even after
     * the TTL elapses, because they are still listed as connected.
     */
    @Test
    fun activePeerIsNeverEvicted() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("active-peer"))
        val seamB = loom.join(InMemoryTag("b"))

        val clock = FakeClock()
        val config = SeamReplicatorConfig(
            evictionAfter = 10.milliseconds,
            antiEntropyInterval = 5.milliseconds,
            expectVirtualTime = true,
        )

        val repA = replicatorFor(rawSeamA, backgroundScope, config, clock)
        replicatorFor(seamB, backgroundScope, config, clock)

        testScheduler.advanceUntilIdle()

        val bId = seamB.selfId
        assertTrue(repA.knownPeersForTest.contains(bId), "B should be known")

        // Advance clock and trigger anti-entropy — B is still in seam.peers
        clock.advanceBy(1000L)
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds + 1)

        assertTrue(
            repA.knownPeersForTest.contains(bId),
            "Active peer must not be evicted even after long TTL",
        )
    }

    /**
     * After eviction, a rejoining peer receives a fresh [ReplicatorMessage.FullState]
     * and converges — the replicator treats it as a new first-contact.
     */
    @Test
    fun evictedPeerReceivesFullStateOnRejoin() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("rejoin-test"))
        val rawSeamB = loom.join(InMemoryTag("b"))

        val clock = FakeClock()
        val config = SeamReplicatorConfig(
            evictionAfter = 10.milliseconds,
            antiEntropyInterval = 5.milliseconds,
            expectVirtualTime = true,
        )

        // Control A's peer view independently of the loom
        val controlledPeers = MutableStateFlow(loom.peers.value)
        val seamA = ControllableSeam(rawSeamA, controlledPeers)

        val repA = replicatorFor(seamA, backgroundScope, config, clock)
        val repB = replicatorFor(rawSeamB, backgroundScope, config, clock)

        // A applies some state while B is present
        repA.apply(repA.state.value.inc(repA.replica, 10L))
        testScheduler.advanceUntilIdle()
        assertEquals(10L, repB.state.value.value, "B should have A's state")

        // B disappears from A's peers view
        controlledPeers.value = setOf(rawSeamA.selfId)
        testScheduler.advanceUntilIdle()

        // Advance clock past eviction TTL + trigger anti-entropy
        clock.advanceBy(50L)
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds + 1)

        assertFalse(repA.knownPeersForTest.contains(rawSeamB.selfId), "B should be evicted")

        // A applies more state while B is gone
        repA.apply(repA.state.value.inc(repA.replica, 5L))
        testScheduler.advanceUntilIdle()

        // B rejoins — appears back in A's peers
        controlledPeers.value = setOf(rawSeamA.selfId, rawSeamB.selfId)
        testScheduler.advanceUntilIdle()

        // B should now receive a fresh FullState and converge to 15
        assertEquals(15L, repB.state.value.value, "Rejoined B should converge via FullState")
    }
}
