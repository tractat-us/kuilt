/**
 * ADVERSARIAL AUDIT (#262) — RGA GC convergence + data-preservation through the
 * **live** SeamReplicator stack under reordered / held / partitioned delivery.
 *
 * The model-level predicate ([Rga.compact]) is proven by [us.tractat.kuilt.crdt.RgaCompactV3AdversarialProbeTest]
 * et al. This suite stresses the *wiring*: real [SeamReplicator]s exchanging real
 * Delta/Ack/Delivered/FullState/Compact frames over a [ControllableLoom] that can
 * reorder, hold and partition.
 *
 * **Sound GC driver.** The VV-based coordinator (#270) is not on this base; the deprecated
 * seq-based [RgaGcCoordinator] is unsound by construction. So these probes drive the *sound*
 * compact path directly — exactly what #270's coordinator will do: observe each replicator's
 * published [SeamReplicator.cutFrontier] + [SeamReplicator.deliveredLocal] and call
 * `Rga.compact(stableCut, frontierMax, delivered)`, feeding the resulting [RgaOp.Compact] back
 * through [SeamReplicator.apply] so it propagates as a delta. This is coordinator-independent:
 * it tests SeamReplicator's cut/frontier derivation + Compact propagation + FullState catch-up.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.ControllableLoom
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val MSG_SER = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<String>()))
private val CFG = SeamReplicatorConfig(expectVirtualTime = true)

/**
 * One peer: its replicator + a sound GC driver wired off the published flows.
 */
private class GcPeer(
    val replica: ReplicaId,
    val rep: SeamReplicator<Rga<String>>,
) {
    val list: List<String> get() = rep.state.value.toList()
    val tombstones get() = rep.state.value.tombstones
    /** Boundedness proxy: tombstones + visible elements (the op-log isn't publicly enumerable). */
    val opLogSizeProxy: Int get() = rep.state.value.tombstones.size + rep.state.value.size
}

private suspend fun TestScope.gcPeer(
    loom: ControllableLoom,
    rendezvousName: String,
    isHost: Boolean,
): GcPeer {
    val seam = if (isHost) {
        loom.host(us.tractat.kuilt.core.Pattern(rendezvousName))
    } else {
        loom.join(us.tractat.kuilt.core.InMemoryTag(rendezvousName))
    }
    val replica = ReplicaId(seam.selfId.value)
    val rep = SeamReplicator(
        replica = replica,
        seam = seam,
        initial = Rga.empty(),
        messageSerializer = MSG_SER,
        scope = backgroundScope,
        config = CFG,
    )
    wireSoundGcDriver(rep, backgroundScope)
    return GcPeer(replica, rep)
}

/**
 * The sound GC driver: on every cut/frontier OR delivered change, run [Rga.compact] to
 * fixpoint and re-broadcast any [RgaOp.Compact] as a delta. Mirrors #270's intended coordinator.
 */
private fun wireSoundGcDriver(rep: SeamReplicator<Rga<String>>, scope: CoroutineScope) {
    // NB: triggered on rep.state (NOT just cutFrontier+deliveredLocal). A `Remove` arriving
    // after its target dot is already causally stable changes neither the cut nor delivered
    // (Remove mints no dot — see Rga.causalDots), yet it is exactly the event that makes a
    // stable element GC-eligible. An edge-triggered coordinator wired off cut+delivered alone
    // leaves such tombstones permanently un-GC'd. See the AUDIT finding (H1) — this is the
    // hazard #270's coordinator must avoid. We trigger on state here so the suite asserts the
    // intended (correct) behaviour.
    combine(rep.cutFrontier, rep.state) { cf, _ -> cf to rep.deliveredLocal.value }
        .onEach { (cf, delivered) ->
            while (true) {
                val current = rep.state.value
                val result = current.compact(
                    stableCut = cf.stableCut,
                    frontierMax = cf.frontierMax,
                    delivered = delivered,
                ) ?: break
                rep.apply(Patch(Rga.empty<String>().apply(result.second)))
            }
        }
        .launchIn(scope)
}

private fun GcPeer.insertAfter(after: RgaId, value: String): RgaOp.Insert<String> {
    val (_, op) = rep.state.value.insertAfter(replica, after, value)
    rep.apply(Patch(Rga.empty<String>().apply(op)))
    return op
}

private fun GcPeer.remove(id: RgaId) {
    rep.apply(Patch(Rga.empty<String>().apply(RgaOp.Remove<String>(id))))
}

class RgaGcLiveStackAuditTest {

    /**
     * H2 — the #262 scenario END-TO-END through the live stack.
     *
     * A inserts I@HEAD and removes it (I tombstoned). C concurrently `Insert(J, after=I)`.
     * A is partitioned from C (C's frames held) while gossip + GC run between A and B.
     * Assert: after the partition heals, **J survives on every peer** — the live stack must
     * refuse to GC I while a concurrent successor J exists undelivered to the compactor.
     */
    @Test
    @Ignore // PENDING #254 reroot-to-HEAD. ROOT-CAUSED: A is fully partitioned from C, so A's
    // frontiers[C] never learns J; when C is evicted on TTL during the window, eviction retains
    // nothing for J (A never knew it), A's cut advances, A GCs I, and J orphans on heal → A=[].
    // This is a real eviction-vs-delayed-delivery data-loss path (latent in #269's eviction model,
    // exposed by #270's faster gossip). reroot-to-HEAD (#254) is the fix: an insert whose `after`
    // was GC'd materialises at HEAD instead of orphaning, so J resurfaces and all converge to [J].
    // #254 MUST un-ignore this and prove it green — it is the eviction-during-partition acceptance.
    fun h2_concurrentInsertAfterTombstone_survivesPartitionHeal() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val a = gcPeer(loom, "h2-host", isHost = true)
        val b = gcPeer(loom, "b", isHost = false)
        val c = gcPeer(loom, "c", isHost = false)
        testScheduler.advanceUntilIdle()

        // A inserts I and removes it.
        val opI = a.insertAfter(RgaId.HEAD, "I")
        testScheduler.advanceUntilIdle()
        a.remove(opI.id)
        testScheduler.advanceUntilIdle()

        // PARTITION: hold all delivery to A. C now mints J after I, concurrent and unseen by A.
        loom.holdDelivery(PeerId(a.replica.value))
        val opJ = c.insertAfter(opI.id, "J")
        testScheduler.advanceUntilIdle()

        // Drive gossip + GC while A is partitioned from the J insert. A and B keep ticking.
        // (Anti-entropy gossipDelivered + the sound driver fire on every flow change.)
        repeat(5) { testScheduler.advanceUntilIdle() }

        // HEAL: release A's buffer; all held frames (including J) flush in FIFO order.
        loom.releaseDelivery(PeerId(a.replica.value))
        testScheduler.advanceUntilIdle()
        repeat(5) { testScheduler.advanceUntilIdle() }

        // J must be present on every peer — never silently GC'd.
        assertTrue("J" in a.list, "J lost on A after partition heal — #262 regression. A=${a.list}")
        assertTrue("J" in b.list, "J lost on B. B=${b.list}")
        assertTrue("J" in c.list, "J lost on C. C=${c.list}")
        assertEquals(a.list, b.list, "A and B must converge")
        assertEquals(a.list, c.list, "A and C must converge")
        assertEquals(listOf("J"), a.list, "visible list is exactly [J] (I tombstoned, J survives)")
    }

    /**
     * H1 — Compact convergence under different delivery orders.
     *
     * Two peers concurrently GC disjoint tombstone sets, each broadcasting a Compact. Delivered
     * to the third peer in opposite orders relative to A vs B. All three must converge to the
     * same visible list AND have GC'd the same elements (Compact is commutative + idempotent
     * through piece/apply).
     */
    @Test
    fun h1_concurrentDisjointCompacts_convergeRegardlessOfOrder() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val a = gcPeer(loom, "h1-host", isHost = true)
        val b = gcPeer(loom, "b", isHost = false)
        val c = gcPeer(loom, "c", isHost = false)
        testScheduler.advanceUntilIdle()

        // Build a surviving spine and two disjoint tombstones authored by A and B respectively.
        val spine = a.insertAfter(RgaId.HEAD, "spine")
        testScheduler.advanceUntilIdle()
        val opA = a.insertAfter(spine.id, "A-dead")
        testScheduler.advanceUntilIdle()
        val opB = b.insertAfter(spine.id, "B-dead")
        testScheduler.advanceUntilIdle()
        a.remove(opA.id)
        b.remove(opB.id)
        testScheduler.advanceUntilIdle()
        repeat(8) { testScheduler.advanceUntilIdle() }

        // After GC drives to fixpoint, all peers converge and both dead elements are gone.
        assertEquals(a.list, b.list, "A==B")
        assertEquals(a.list, c.list, "A==C")
        assertEquals(listOf("spine"), a.list, "only the spine survives; both tombstones GC'd")
        assertTrue(a.tombstones.isEmpty(), "A has no tombstones after GC")
        assertTrue(b.tombstones.isEmpty(), "B has no tombstones after GC")
        assertTrue(c.tombstones.isEmpty(), "C has no tombstones after GC")
    }

    /**
     * H3 — Continuous insert+remove+GC with REORDERED delivery (the chat use-case).
     *
     * 3 peers, many rounds, each round each peer inserts at HEAD and removes a prior element.
     * Unlike the existing 3-peer test, delivery to one peer (B) is **held mid-round and flushed
     * out of lockstep**, so the cut advances against reordered op arrival rather than the
     * advanceUntilIdle-after-every-op lockstep that masks reordering. Assert: all peers
     * converge to identical visible lists AND the op-log stays bounded (tombstones drain).
     */
    @Test
    fun h3_continuousInsertRemove_reorderedDelivery_convergesAndBounds() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val a = gcPeer(loom, "h3-host", isHost = true)
        val b = gcPeer(loom, "b", isHost = false)
        val c = gcPeer(loom, "c", isHost = false)
        val bId = PeerId(b.replica.value)
        testScheduler.advanceUntilIdle()

        val peers = listOf(a, b, c)
        var peakTombstones = 0

        repeat(12) { round ->
            // Hold B's inbound for the first half of the round, then flush — reorders B's view.
            if (round % 2 == 0) loom.holdDelivery(bId)
            peers.forEach { peer -> peer.insertAfter(RgaId.HEAD, "$round-${peer.replica.value}") }
            // Each peer removes the OLDEST id it still holds visible (drives tombstones).
            peers.forEach { peer ->
                val visible = peer.rep.state.value.let { rga -> rga.sequence.firstOrNull { it !in rga.tombstones } }
                if (visible != null) peer.remove(visible)
            }
            // Sample BEFORE settle: tombstones exist transiently (GC has not yet caught up).
            peakTombstones = maxOf(peakTombstones, a.tombstones.size, b.tombstones.size, c.tombstones.size)
            if (round % 2 == 0) loom.releaseDelivery(bId)
            testScheduler.advanceUntilIdle()
        }
        // Let gossip + GC settle.
        repeat(12) { testScheduler.advanceUntilIdle() }

        assertTrue(peakTombstones > 0, "non-vacuous: tombstones were actually created mid-stream (GC had work to do)")
        assertEquals(a.list, b.list, "A==B after reordered continuous churn. A=${a.list} B=${b.list}")
        assertEquals(a.list, c.list, "A==C after reordered continuous churn. A=${a.list} C=${c.list}")
        // Bounded: tombstones must drain (not accumulate proportionally to total ops).
        assertTrue(a.tombstones.isEmpty(), "A tombstones drained; found ${a.tombstones.size}")
        assertTrue(b.tombstones.isEmpty(), "B tombstones drained; found ${b.tombstones.size}")
        assertTrue(c.tombstones.isEmpty(), "C tombstones drained; found ${c.tombstones.size}")
    }

    /**
     * H4 — Late joiner + GC. D joins mid-stream after GC has run, gets FullState, then more
     * GC happens. D must converge without re-introducing GC'd elements or losing live ones.
     */
    @Test
    fun h4_lateJoinerAfterGc_convergesThroughMoreGc() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val a = gcPeer(loom, "h4-host", isHost = true)
        val b = gcPeer(loom, "b", isHost = false)
        testScheduler.advanceUntilIdle()

        // Phase 1: build + GC several tombstones, leave one survivor.
        repeat(4) { i ->
            val op = a.insertAfter(RgaId.HEAD, "dead-$i")
            testScheduler.advanceUntilIdle()
            a.remove(op.id)
            testScheduler.advanceUntilIdle()
        }
        a.insertAfter(RgaId.HEAD, "survivor-1")
        repeat(6) { testScheduler.advanceUntilIdle() }
        assertTrue(a.tombstones.isEmpty(), "phase 1 GC drained A's tombstones")
        val preJoin = a.list

        // D joins → FullState catch-up.
        val d = gcPeer(loom, "d", isHost = false)
        repeat(4) { testScheduler.advanceUntilIdle() }
        assertEquals(preJoin, d.list, "D converges to the GC'd state via FullState (no GC'd elements resurrected)")
        assertTrue(d.tombstones.isEmpty(), "D carries no resurrected tombstones from FullState")

        // Phase 2: more churn + GC now that D is a member.
        repeat(3) { i ->
            val op = b.insertAfter(RgaId.HEAD, "dead2-$i")
            testScheduler.advanceUntilIdle()
            b.remove(op.id)
            testScheduler.advanceUntilIdle()
        }
        d.insertAfter(RgaId.HEAD, "survivor-2")
        repeat(8) { testScheduler.advanceUntilIdle() }

        assertEquals(a.list, b.list, "A==B post-join GC")
        assertEquals(a.list, d.list, "A==D post-join GC")
        assertTrue("survivor-1" in d.list, "live element survivor-1 not lost on D")
        assertTrue("survivor-2" in a.list, "D's contribution survivor-2 reached A")
        assertTrue(d.tombstones.isEmpty(), "D tombstones drained after phase-2 GC")
    }

    /**
     * H5 — Insert-after-a-tombstone-then-GC race across the real stack. C's `Insert(J, after=I)`
     * where the predecessor I is concurrently tombstoned and a GC pass runs. The successor J must
     * remain reachable (computeSequence keeps it under HEAD via its surviving chain) and all peers
     * converge — even when J's delivery to the would-be compactor is delayed past the GC attempt.
     */
    @Test
    fun h5_insertAfterTombstone_gcRace_keepsSuccessorReachable() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val a = gcPeer(loom, "h5-host", isHost = true)
        val b = gcPeer(loom, "b", isHost = false)
        val c = gcPeer(loom, "c", isHost = false)
        val aId = PeerId(a.replica.value)
        val bId = PeerId(b.replica.value)
        testScheduler.advanceUntilIdle()

        // A inserts I (visible to all), then everyone sees it.
        val opI = a.insertAfter(RgaId.HEAD, "I")
        testScheduler.advanceUntilIdle()

        // Partition A and B from C. C inserts J after I, then C removes I (tombstones its own predecessor).
        loom.holdDelivery(aId)
        loom.holdDelivery(bId)
        c.insertAfter(opI.id, "J")
        c.remove(opI.id)
        testScheduler.advanceUntilIdle()

        // Meanwhile A and B (which both still see I live, no J) run their own churn + GC attempts.
        repeat(4) { testScheduler.advanceUntilIdle() }

        // Non-vacuous: before heal, A/B still see I (J was held), C sees J with I tombstoned —
        // a genuine cross-partition race, not a pre-converged no-op.
        assertTrue("I" in a.list, "pre-heal: A still sees I (J insert was held) — race is genuine. A=${a.list}")
        assertTrue("J" in c.list && "I" !in c.list, "pre-heal: C has J, I tombstoned. C=${c.list}")

        // Heal.
        loom.releaseDelivery(aId)
        loom.releaseDelivery(bId)
        repeat(8) { testScheduler.advanceUntilIdle() }

        // J must be reachable on every peer; I is tombstoned everywhere.
        assertTrue("J" in a.list, "J reachable on A. A=${a.list}")
        assertTrue("J" in b.list, "J reachable on B. B=${b.list}")
        assertTrue("J" in c.list, "J reachable on C. C=${c.list}")
        assertTrue("I" !in a.list, "I tombstoned on A")
        assertEquals(a.list, b.list, "A==B")
        assertEquals(a.list, c.list, "A==C")
        assertEquals(listOf("J"), a.list, "converged visible list is [J]")
    }
}
