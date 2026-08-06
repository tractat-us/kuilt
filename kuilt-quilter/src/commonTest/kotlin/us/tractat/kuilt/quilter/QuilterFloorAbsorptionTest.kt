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
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A **second replica** absorbing a compaction floor it never held the ops for (#2179).
 *
 * `QuilterCausalFloorTest` pins the frontier arithmetic and the call site, but every one of its
 * probes is single-replica: the replica that floors the dots is the replica whose frontier is
 * read. The path the capability exists for is the other one — a peer that never received the
 * floored `Insert`s at all, learning of them only as a floor, and being entitled to report them
 * delivered anyway.
 *
 * "Delivered" in the causal-stability barrier means *no op at or below this seq will ever be
 * applied here later*, and an absorbed floor guarantees that **more strongly** than having
 * applied the ops: a late raw op beneath it is permanently suppressed rather than resurrected.
 * That is what makes the report sound, and it is asserted here rather than argued.
 *
 * The outcome is the reason it matters. A peer that reported `0` for the floored author would
 * hold the pair's stable cut at `0` for that author, so
 * [us.tractat.kuilt.crdt.Rga.compact]'s condition 2 could never be satisfied for anything that
 * author minted afterwards — tombstone collection stalls for the life of the session.
 *
 * **The tombstone is deliberately minted after B joins.** A stable cut is monotonic, so A's solo
 * phase alone lifts it past everything A had already written; a tombstone from that phase is
 * collected whatever B reports, and asserting on one would be a probe that cannot fail. Only a
 * cut that has to *rise* after B is in the room reads B's row.
 */
class QuilterFloorAbsorptionTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    private val messageSerializer = QuiltMessage.serializer(Rga.wireSerializer(serializer<String>()))

    private fun replicatorFor(replica: ReplicaId, seam: Seam, scope: CoroutineScope): Quilter<Rga<String>> {
        val replicator = Quilter(
            replica = replica,
            seam = seam,
            initial = Rga.empty(),
            messageSerializer = messageSerializer,
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true),
        )
        RgaGcCoordinator(
            state = replicator.state,
            cutFrontier = replicator.cutFrontier,
            delivered = replicator.deliveredLocal,
            applyCompaction = { patch -> replicator.apply(patch) },
            scope = scope,
        )
        return replicator
    }

    /** Append [value] at the tail of [this]'s log and broadcast the op. Returns the new id. */
    private fun Quilter<Rga<String>>.append(author: ReplicaId, value: String): RgaId {
        val tail = state.value.entries().lastOrNull()?.first ?: RgaId.HEAD
        val (_, op) = state.value.insertAfter(author, tail, value)
        apply(Patch(Rga.empty<String>().apply(op)))
        return op.id
    }

    @Test
    fun aPeerThatNeverHeldTheFlooredOpsAbsorbsTheFloorAndTombstoneGcResumes() = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("floor-absorption"))
        val repA = replicatorFor(a, seamA, backgroundScope)

        // A appends four entries and windows the oldest two away — alone, before B exists.
        val ids = List(4) { i -> repA.append(a, "v$i") }
        testScheduler.advanceUntilIdle()
        repA.mutate { rga -> rga.dropWindow(a, setOf(ids[0], ids[1]))!!.second }
        testScheduler.advanceUntilIdle()

        // B joins only now, so the raw (a,1) and (a,2) Inserts were never on a wire it was
        // listening to and are already gone from A. Its only route to that history is the floor
        // A carries — which is why this cannot be staged by holding a peer's inbound queue: a
        // held queue is released in order and would deliver the very ops B must never have seen.
        val seamB = loom.join(InMemoryTag("b"))
        val repB = replicatorFor(b, seamB, backgroundScope)
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertEquals(listOf("v2", "v3"), repA.state.value.toList(), "precondition: A windowed v0/v1 away") },
            { assertEquals(listOf("v2", "v3"), repB.state.value.toList(), "B did not converge with A") },
            {
                assertEquals(
                    2L,
                    repB.state.value.causalFloor()[a],
                    "precondition: B must have absorbed A's floor, not merely its visible entries",
                )
            },
            {
                assertTrue(
                    repB.state.value.causalDots().none { it == Dot(a, 1L) || it == Dot(a, 2L) },
                    "precondition: the floored dots must be absent from B's dots, or the walk below " +
                        "never needed the floor; got ${repB.state.value.causalDots()}",
                )
            },
            {
                assertEquals(
                    4L,
                    repB.deliveredLocal.value[a],
                    "a peer that absorbed the floor must report the swallowed seqs as delivered; " +
                        "counting only its dots collapses a's frontier to 0 and stalls every GC",
                )
            },
        )

        // A late raw op beneath the absorbed floor: suppressed, not resurrected. This is what
        // entitles B to have reported those seqs delivered in the first place — "delivered" means
        // "nothing at or below this seq will ever apply here", which an absorbed floor guarantees
        // and a merely-applied op does not.
        //
        // Minted the way A minted it, on a fresh log, so it carries the identical dot (a,1) and
        // the identical `after = HEAD`: it IS the op the window dropped, arriving late from a peer
        // that never heard about the drop.
        val (_, lateOp) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "v0")
        repB.apply(Patch(Rga.empty<String>().apply(lateOp)))
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertEquals(Dot(a, 1L), lateOp.id.dot, "precondition: the stray must be the dot the floor swallowed") },
            { assertEquals(listOf("v2", "v3"), repB.state.value.toList(), "a floored entry was resurrected on B") },
            {
                assertTrue(
                    Dot(a, 1L) !in repB.state.value.causalDots(),
                    "the stray op was absorbed rather than suppressed, so B's frontier would then rest " +
                        "on having applied it — the floor's guarantee is what has to do that work",
                )
            },
            { assertEquals(4L, repB.deliveredLocal.value[a], "B's frontier must not move on a suppressed op") },
        )

        // The outcome. A appends a fresh tail AFTER B joined and then tombstones it, so collecting
        // it needs the stable cut to rise to a seq A minted while B was in the room — which it can
        // only do if B's row reports the floored seqs as delivered. The tail, because condition 4
        // refuses to collect an element that still has a surviving successor.
        val fresh = repA.append(a, "v4")
        testScheduler.advanceUntilIdle()
        val (_, removeOp) = requireNotNull(repA.state.value.removeAt(2)) { "expected v4 to be removable" }
        repA.apply(Patch(Rga.empty<String>().apply(removeOp)))
        testScheduler.advanceUntilIdle()

        assertAll(
            {
                assertTrue(
                    repA.cutFrontier.value.stableCut.contains(fresh.dot),
                    "precondition: the tombstoned element's dot ${fresh.dot} must be one the cut had " +
                        "to RISE to reach, and it did not: ${repA.cutFrontier.value.stableCut}",
                )
            },
            {
                assertTrue(
                    repA.state.value.tombstones.isEmpty(),
                    "tombstone GC did not resume across the pair: ${repA.state.value.tombstones}. " +
                        "Condition 2 needs the stable cut to reach ${fresh.dot}, which it can only do " +
                        "if B reports the floored seqs as delivered",
                )
            },
            { assertEquals(listOf("v2", "v3"), repA.state.value.toList(), "GC must collect the tombstone, not the entry") },
            { assertEquals(repA.state.value.toList(), repB.state.value.toList(), "the pair must stay converged") },
        )

        seamB.close()
    }
}
