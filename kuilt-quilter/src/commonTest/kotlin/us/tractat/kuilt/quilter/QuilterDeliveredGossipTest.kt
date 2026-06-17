/**
 * Delivered-vector gossip (#268): a [Quilter] over an [Rga] derives its
 * contiguous **delivered** version vector from applied ops and gossips it via
 * [QuiltMessage.Delivered]; peers absorb it into their matrix-clock [frontiers].
 *
 * All tests use [UnconfinedTestDispatcher] with [QuilterConfig.expectVirtualTime] = true,
 * per `docs/testing-coroutine-determinism.md`.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals

private val GOSSIP_CFG = QuilterConfig(expectVirtualTime = true)
private val RGA_MSG_SER = QuiltMessage.serializer(Rga.wireSerializer(serializer<String>()))

private fun rgaRep(seam: Seam, scope: CoroutineScope): Quilter<Rga<String>> =
    Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = Rga.empty(),
        messageSerializer = RGA_MSG_SER,
        scope = scope,
        config = GOSSIP_CFG,
    )

/** Inserts [value] at HEAD on [rep], broadcasting the resulting op as a delta. */
private fun Quilter<Rga<String>>.insertHead(value: String): RgaId {
    val (_, op) = state.value.insertAfter(replica, RgaId.HEAD, value)
    apply(Patch(Rga.empty<String>().apply(op)))
    return op.id
}

class QuilterDeliveredGossipTest {

    @Test
    fun deliveredLocalReflectsAppliedOps() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("delivered-local"))
        val repA = rgaRep(seamA, backgroundScope)
        val a = repA.replica

        repA.insertHead("x")
        repA.insertHead("y")
        testScheduler.advanceUntilIdle()

        // Two contiguous self dots ⇒ delivered[a] == 2.
        assertEquals(VersionVector.of(mapOf(a to 2L)), repA.deliveredLocal.value)
    }

    @Test
    fun gossipPopulatesPeerFrontiers() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("gossip-frontiers"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = rgaRep(seamA, backgroundScope)
        val repB = rgaRep(seamB, backgroundScope)
        val a = repA.replica

        repA.insertHead("x")
        repA.insertHead("y")
        testScheduler.advanceUntilIdle()

        // B delivered both of A's ops AND received A's gossiped Delivered VV.
        assertEquals(VersionVector.of(mapOf(a to 2L)), repB.deliveredLocal.value)
        assertEquals(
            VersionVector.of(mapOf(a to 2L)),
            repB.frontiersForTest[PeerId(a.value)],
            "B's matrix row for A reflects A's gossiped delivered vector",
        )
    }

    @Test
    fun crossAuthorMatrixConverges() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("gossip-cross-author"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = rgaRep(seamA, backgroundScope)
        val repB = rgaRep(seamB, backgroundScope)
        val a = repA.replica
        val b = repB.replica

        repB.insertHead("b1")
        repB.insertHead("b2")
        testScheduler.advanceUntilIdle()
        // A applies after B so A's final gossip carries the fully-converged vector;
        // an anti-entropy tick then re-gossips both sides' converged delivered VVs.
        repA.insertHead("a1")
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(GOSSIP_CFG.antiEntropyInterval.inWholeMilliseconds + 1)
        testScheduler.advanceUntilIdle()

        val expected = VersionVector.of(mapOf(a to 1L, b to 2L))
        // Each peer has delivered both authors' ops (recomputed from merged state) …
        assertEquals(expected, repA.deliveredLocal.value)
        assertEquals(expected, repB.deliveredLocal.value)
        // … and the anti-entropy re-gossip carried each peer's converged row to the other.
        assertEquals(expected, repA.frontiersForTest[PeerId(b.value)])
        assertEquals(expected, repB.frontiersForTest[PeerId(a.value)])
    }
}
