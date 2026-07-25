/**
 * Restart-determinism for the capability board (H8, design §14.6; regression for #1666).
 *
 * The capability board is an [us.tractat.kuilt.crdt.EphemeralMap] whose per-replica clock, before
 * the fix, restarted from zero on every process incarnation. A peer that crashed and restarted
 * therefore re-advertised behind its dead incarnation's higher clock, so pre-restart observers
 * kept the *dead* [CapSet] permanently — a permanent pre/post-restart divergence in
 * [WarpNode.capabilityView], hence in [WarpNode.eligiblePeers] and the ring owner it drives.
 *
 * The fix seeds the capability clock's high bits with a required per-boot `epoch`, so a restarted
 * peer's advertisement always out-clocks its dead incarnation's — the observer reconverges to the
 * fresh caps by clock alone, *without* waiting for the TTL to evict the dead slot.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WarpCapabilityRestartTest {

    private val cfg = QuilterConfig(
        antiEntropyInterval = 100.milliseconds,
        fullStateRetryInterval = 150.milliseconds,
        expectVirtualTime = true,
    )

    private val aId = PeerId("A")
    private val bId = PeerId("B")
    private val roster = setOf(aId, bId)

    private val gpu = CapSet(tokens = setOf("GPU"), attributes = mapOf("region" to "us-east"))
    private val cpu = CapSet(tokens = setOf("CPU"), attributes = mapOf("region" to "us-west"))
    private val gpuAffinity = Affinity.has("GPU")

    /**
     * A→B: B is the pre-restart observer; A advertises GPU, restarts (fresh boot, higher epoch),
     * then advertises CPU. Without advancing the capability TTL at all, B must reconverge to A's
     * *new* CapSet — and A's restarted node and B must agree on both the view and the eligible
     * set, i.e. no permanent divergence. Fails on `main` (B stays pinned to the dead GPU slot).
     */
    @Test
    fun restartedPeerReconvergesWithoutTtlAdvance() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        // Observer B.
        val seamB = FakeSeam(selfId = bId, initialPeers = roster)
        val nodeB = WarpNode(
            selfId = bId, seam = seamB, rosterFlow = MutableStateFlow(roster), scope = backgroundScope,
            quilterConfig = cfg, clock = clock, registry = OpRegistry(), epoch = 0L,
        )

        // A's first incarnation (epoch 1) advertises GPU; relay its frames to B.
        val seamA1 = FakeSeam(selfId = aId, initialPeers = roster)
        val nodeA1 = WarpNode(
            selfId = aId, seam = seamA1, rosterFlow = MutableStateFlow(roster), scope = backgroundScope,
            quilterConfig = cfg, clock = clock, registry = OpRegistry(), epoch = 1L,
        )
        nodeA1.advertiseCapabilities(gpu)
        drainAntiEntropy(cfg.antiEntropyInterval, rounds = 3)
        relay(seamA1, from = aId, to = seamB)
        drainAntiEntropy(cfg.antiEntropyInterval, rounds = 3)
        assertEquals(gpu, nodeB.capabilityView()[aId], "B sees A's pre-restart GPU caps")

        // A crashes and restarts: SAME peer id, a fresh boot whose per-boot counter restarts from
        // zero, but a strictly-greater epoch. No TTL advance below (the dead slot never expires).
        nodeA1.close()
        val seamA2 = FakeSeam(selfId = aId, initialPeers = roster)
        val nodeA2 = WarpNode(
            selfId = aId, seam = seamA2, rosterFlow = MutableStateFlow(roster), scope = backgroundScope,
            quilterConfig = cfg, clock = clock, registry = OpRegistry(), epoch = 2L,
        )
        nodeA2.advertiseCapabilities(cpu)
        drainAntiEntropy(cfg.antiEntropyInterval, rounds = 3)
        relay(seamA2, from = aId, to = seamB)
        drainAntiEntropy(cfg.antiEntropyInterval, rounds = 3)

        assertAll(
            { assertEquals(cpu, nodeB.capabilityView()[aId], "B reconverges to the restart's CPU caps by clock, not by TTL") },
            { assertEquals(nodeA2.capabilityView()[aId], nodeB.capabilityView()[aId], "A2 and B agree on A's caps — no permanent divergence") },
            // eligiblePeers reconverges: A is no longer GPU-eligible on either peer.
            { assertEquals(emptySet(), nodeB.eligiblePeers(gpuAffinity), "B: restarted A is no longer GPU-eligible") },
            { assertEquals(nodeA2.eligiblePeers(gpuAffinity), nodeB.eligiblePeers(gpuAffinity), "A2 and B agree on the eligible set") },
        )

        nodeA2.close(); nodeB.close()
    }

    /** Deliver every frame [from] a peer's [FakeSeam] broadcasts to another seam [to]. */
    private suspend fun relay(source: FakeSeam, from: PeerId, to: FakeSeam) {
        for (payload in source.broadcasts) to.deliver(from = from, payload = payload)
    }
}
