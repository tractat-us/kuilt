/**
 * The causal-stability cut + frontier flows and the eviction-safe retained frontier
 * (#269, ADR-003 addendum v3 §4). A [SeamReplicator] over an [Rga] derives, from its own
 * [SeamReplicator.deliveredLocal] and the gossiped [frontiers] matrix:
 *
 * - `stableCut S` = min over live peers ∪ self (monotonic),
 * - `frontierMax F` = max(F_live, retainedFrontier),
 *
 * published together as [SeamReplicator.cutFrontier]. Eviction folds a departing peer's
 * frontier into `retainedFrontier` (retain-capture-before-drop, W1) so `F` never falls
 * below a known-to-exist dot the compactor has not delivered — the #275 refusal.
 *
 * Peers' claimed delivery is injected as crafted [ReplicatorMessage.Delivered] frames so a
 * single replica's view can be driven deterministically (atomic [InMemoryLoom] broadcast
 * cannot otherwise produce "knows of a dot but has not delivered it").
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val RGA_MSG_SER = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<String>()))

private fun rgaRep(
    seam: Seam,
    scope: CoroutineScope,
    config: SeamReplicatorConfig = SeamReplicatorConfig(expectVirtualTime = true),
    clock: MonotonicMillis = FakeClock(), // frozen by default; eviction tests pass an advancing one
): SeamReplicator<Rga<String>> =
    SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = Rga.empty(),
        messageSerializer = RGA_MSG_SER,
        scope = scope,
        config = config,
        clock = clock,
    )

private fun SeamReplicator<Rga<String>>.insertHead(value: String) {
    val (_, op) = state.value.insertAfter(replica, RgaId.HEAD, value)
    apply(Patch(Rga.empty<String>().apply(op)))
}

/** Crafts a [ReplicatorMessage.Delivered] claiming [vector] and broadcasts it from [from]. */
private suspend fun craftDelivered(from: Seam, vector: VersionVector) {
    val msg = ReplicatorMessage.Delivered<Rga<String>>(sender = ReplicaId(from.selfId.value), vector = vector)
    from.broadcast(Cbor.encodeToByteArray(RGA_MSG_SER, msg))
}

/** Wraps a real seam but lets a test override [Seam.peers] (to drop a peer without closing). */
private class CutControllableSeam(
    private val delegate: Seam,
    override val peers: MutableStateFlow<Set<PeerId>>,
) : Seam by delegate

private class FakeClock(private var t: Long = 0L) : MonotonicMillis {
    override fun now(): Long = t
    fun advanceBy(ms: Long) { t += ms }
}

class SeamReplicatorStableCutTest {

    @Test
    fun stableCutIsMinOverLivePeers() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("cut-min"))
        val rawB = loom.join(InMemoryTag("b"))
        val repA = rgaRep(seamA, backgroundScope)
        val a = repA.replica

        repA.insertHead("x"); repA.insertHead("y"); repA.insertHead("z") // self delivered[a] = 3
        // B claims it has only delivered A up to 2 — the laggard pins the cut.
        craftDelivered(rawB, VersionVector.of(mapOf(a to 2L)))
        testScheduler.advanceUntilIdle()

        assertEquals(VersionVector.of(mapOf(a to 2L)), repA.cutFrontier.value.stableCut)
    }

    @Test
    fun stableCutIsMonotonic() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("cut-mono"))
        val rawB = loom.join(InMemoryTag("b"))
        val repA = rgaRep(seamA, backgroundScope)
        val a = repA.replica

        repA.insertHead("x"); repA.insertHead("y"); repA.insertHead("z") // self = 3
        craftDelivered(rawB, VersionVector.of(mapOf(a to 3L)))
        testScheduler.advanceUntilIdle()
        assertEquals(3L, repA.cutFrontier.value.stableCut[a])

        // A laggard re-gossip (claims only 1) must NOT lower the monotonic cut.
        craftDelivered(rawB, VersionVector.of(mapOf(a to 1L)))
        testScheduler.advanceUntilIdle()
        assertEquals(3L, repA.cutFrontier.value.stableCut[a], "stable cut is monotonic — never decreases")
    }

    @Test
    fun frontierMaxIsMaxOverLivePeers() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("frontier-max"))
        val rawB = loom.join(InMemoryTag("b"))
        val repA = rgaRep(seamA, backgroundScope)
        val a = repA.replica

        repA.insertHead("x") // self = 1
        // B reports knowledge of A up to 5 (it relayed dots A has not yet re-derived locally).
        craftDelivered(rawB, VersionVector.of(mapOf(a to 5L)))
        testScheduler.advanceUntilIdle()

        assertEquals(5L, repA.cutFrontier.value.frontierMax[a], "F_live is the max over peers")
    }

    @Test
    fun evictionRetainsUndeliveredDot_frontierMaxHolds() = runTest(UnconfinedTestDispatcher()) {
        // The #275 refusal at the replicator wiring level: B witnesses a dot (c,1) that A has
        // NOT delivered; evicting B must retain it so frontierMax still floors it → condition 3
        // (delivered.dominates(frontierMax)) is false → a compactor refuses GC.
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("evict-retain"))
        val rawB = loom.join(InMemoryTag("b"))
        val c = ReplicaId("c")
        val cDot = Dot(c, 1L)

        val controlledPeers = MutableStateFlow(loom.peers.value)
        val clock = FakeClock()
        val repA = rgaRep(
            CutControllableSeam(seamA, controlledPeers),
            backgroundScope,
            config = SeamReplicatorConfig(
                expectVirtualTime = true,
                evictionAfter = 100.milliseconds,
                antiEntropyInterval = 50.milliseconds,
            ),
            clock = clock,
        )

        controlledPeers.value = loom.peers.value // include B
        // B gossips that it has delivered (c,1) — A relays-knows the dot exists, but never delivered it.
        craftDelivered(rawB, VersionVector.of(mapOf(c to 1L)))
        testScheduler.advanceUntilIdle()
        assertTrue(repA.cutFrontier.value.frontierMax.contains(cDot), "before eviction: F_live carries (c,1)")
        assertEquals(0L, repA.deliveredLocal.value[c], "A never delivered (c,1)")

        // Drop B from the peers view and evict it (clock past TTL + anti-entropy tick).
        controlledPeers.value = controlledPeers.value - PeerId(rawB.selfId.value)
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()

        assertFalse(rawB.selfId in repA.knownPeersForTest.map { it }, "B evicted")
        assertTrue(repA.retainedFrontierForTest.contains(cDot), "W1: eviction retained (c,1)")
        assertTrue(
            repA.cutFrontier.value.frontierMax.contains(cDot),
            "frontierMax must STILL floor (c,1) after eviction — the eviction-safe fix",
        )
        assertFalse(
            repA.deliveredLocal.value.dominates(repA.cutFrontier.value.frontierMax),
            "condition 3 fails → compactor refuses GC of anything depending on (c,1)",
        )
    }

    @Test
    fun retainedReleasedWhenLivePeerWitnesses_R2() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("release-r2"))
        val rawB = loom.join(InMemoryTag("b"))
        val rawD = loom.join(InMemoryTag("d"))
        val c = ReplicaId("c")
        val cDot = Dot(c, 1L)

        val controlledPeers = MutableStateFlow(loom.peers.value)
        val clock = FakeClock()
        val repA = rgaRep(
            CutControllableSeam(seamA, controlledPeers),
            backgroundScope,
            config = SeamReplicatorConfig(
                expectVirtualTime = true,
                evictionAfter = 100.milliseconds,
                antiEntropyInterval = 50.milliseconds,
            ),
            clock = clock,
        )
        controlledPeers.value = loom.peers.value

        // B and D both witness (c,1). Evict B → retained captures it.
        craftDelivered(rawB, VersionVector.of(mapOf(c to 1L)))
        craftDelivered(rawD, VersionVector.of(mapOf(c to 1L)))
        testScheduler.advanceUntilIdle()
        controlledPeers.value = controlledPeers.value - PeerId(rawB.selfId.value)
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()

        // D (live) still witnesses (c,1), so the retained entry is redundant (R2) and dropped,
        // yet frontierMax still floors (c,1) via D's live row.
        assertFalse(repA.retainedFrontierForTest.contains(cDot), "R2: live peer D dominates → retained dropped")
        assertTrue(repA.cutFrontier.value.frontierMax.contains(cDot), "F still carries (c,1) via the live witness D")
    }
}
