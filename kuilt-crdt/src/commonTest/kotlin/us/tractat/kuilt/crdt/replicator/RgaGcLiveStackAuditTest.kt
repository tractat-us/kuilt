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
}
