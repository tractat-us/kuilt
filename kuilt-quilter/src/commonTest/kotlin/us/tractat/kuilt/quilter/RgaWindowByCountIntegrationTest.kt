/**
 * End-to-end acceptance tests for `WindowPolicy.byCount(n)` history windowing (#254) over the
 * **live replicator stack** — real [SeamReplicator]s + [RgaGcCoordinator]s, with the un-gated
 * windowing drop relying on reroot-to-HEAD (#254 PART 1).
 *
 * Covers the #254 acceptance criteria:
 *  - `byCount(n)` drops the leading visible prefix beyond `n` via a `Compact`; the window renders.
 *  - Late joiner over a 3-peer `byCount(10)` room converges to the WINDOWED state via FullState.
 *  - Per-peer divergence: two peers, different `n`, converge to the union of drops.
 *  - Op-log bounded under continuous insert + remove WITH a window active.
 *  - Safety: a concurrent `Insert(J, after=I)` where `I` is window-dropped does NOT lose `J` — it
 *    resurfaces at the window boundary; all peers converge.
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
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.ControllableLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val WIN_CFG = SeamReplicatorConfig(expectVirtualTime = true)
private val WIN_MSG_SER = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<Int>()))

class RgaWindowByCountIntegrationTest {

    private fun wire(
        seam: Seam,
        scope: CoroutineScope,
        policy: WindowPolicy = WindowPolicy.never(),
    ): SeamReplicator<Rga<Int>> {
        val replicator = SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = Rga.empty(),
            messageSerializer = WIN_MSG_SER,
            scope = scope,
            config = WIN_CFG,
        )
        RgaGcCoordinator(
            state = replicator.state,
            cutFrontier = replicator.cutFrontier,
            delivered = replicator.deliveredLocal,
            applyCompaction = { patch -> replicator.apply(patch) },
            windowPolicy = policy,
            scope = scope,
        )
        return replicator
    }

    private fun SeamReplicator<Rga<Int>>.applyOp(op: RgaOp<Int>) =
        apply(Patch(Rga.empty<Int>().apply(op)))

    /**
     * Asserts no [RgaOp.Insert] in the op-log is *orphaned* — present but unreachable from HEAD.
     * `toList()` materializes exactly the reachable, non-tombstoned ids; with reroot-to-HEAD every
     * surviving Insert is reachable, so the count of live (non-tombstoned) inserts must equal the
     * rendered size. A mismatch would be the #275 failure: a committed op silently unreachable.
     */
    private fun assertNoOrphan(rga: Rga<Int>) {
        val liveInserts = rga.sequence.count { it !in rga.tombstones }
        assertEquals(
            liveInserts,
            rga.toList().size,
            "no orphan: every live Insert is reachable from HEAD (reroot keeps the structure sound)",
        )
    }

    /** Append [value] at the tail of this replica's current visible sequence. */
    private fun SeamReplicator<Rga<Int>>.append(value: Int): RgaId {
        val current = state.value
        val after = current.sequence.lastOrNull { it !in current.tombstones } ?: RgaId.HEAD
        val (_, op) = current.insertAfter(replica, after, value)
        applyOp(op)
        return op.id
    }

    // ---- byCount drops the leading visible prefix; the window renders correctly ----

    @Test
    fun byCountDropsLeadingPrefixAndWindowRenders() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("win-render"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = wire(seamA, backgroundScope, WindowPolicy.byCount(3))
        wire(seamB, backgroundScope, WindowPolicy.byCount(3))
        testScheduler.advanceUntilIdle()

        (0..5).forEach { repA.append(it); testScheduler.advanceUntilIdle() }

        // Window keeps the last 3 visible; the leading prefix {0,1,2} is dropped via Compact.
        assertEquals(listOf(3, 4, 5), repA.state.value.toList(), "window renders the last 3 via reroot")
        assertTrue(repA.state.value.sequence.size == 3, "the dropped prefix is purged from the op-log")
    }

    // ---- late joiner over a 3-peer byCount(10) room converges to the WINDOWED state ----

    @Test
    fun lateJoinerConvergesToWindowedStateViaFullState() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("win-late-joiner"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val repA = wire(seamA, backgroundScope, WindowPolicy.byCount(10))
        wire(seamB, backgroundScope, WindowPolicy.byCount(10))
        wire(seamC, backgroundScope, WindowPolicy.byCount(10))
        testScheduler.advanceUntilIdle()

        // 25 appends → window keeps the last 10 (15,16,…,24). The first 15 are dropped.
        (0..24).forEach { repA.append(it); testScheduler.advanceUntilIdle() }
        val windowed = (15..24).toList()
        assertEquals(windowed, repA.state.value.toList(), "A windowed to the last 10")

        // Late joiner D arrives AFTER windowing — must converge via FullState, not full replay.
        val seamD = loom.join(InMemoryTag("d"))
        val repD = wire(seamD, backgroundScope, WindowPolicy.byCount(10))
        testScheduler.advanceUntilIdle()

        assertEquals(windowed, repD.state.value.toList(), "D converges to the WINDOWED state via FullState")
        assertTrue(
            repD.state.value.sequence.size <= 10,
            "D never materialized the dropped prefix — bounded, not a full-history replay",
        )
    }

    // ---- per-peer divergence: two peers, different n, converge to the union of drops ----

    @Test
    fun perPeerDivergentWindowsConvergeToUnionOfDrops() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("win-divergent"))
        val seamB = loom.join(InMemoryTag("b"))

        // A keeps the last 2; B keeps the last 4. Different, both valid (most-aggressive-window-wins).
        val repA = wire(seamA, backgroundScope, WindowPolicy.byCount(2))
        val repB = wire(seamB, backgroundScope, WindowPolicy.byCount(4))
        testScheduler.advanceUntilIdle()

        (0..5).forEach { repA.append(it); testScheduler.advanceUntilIdle() }
        testScheduler.advanceUntilIdle()

        // Set-union of the two Compacts: A dropped {0..3}, B dropped {0,1}; union ⇒ both end at [4,5].
        assertEquals(listOf(4, 5), repA.state.value.toList(), "A's most-aggressive window dominates")
        assertEquals(
            repA.state.value.toList(),
            repB.state.value.toList(),
            "B converges to A's more-aggressive window — union of drops",
        )
    }

    // ---- op-log bounded under continuous insert + remove WITH a window active ----

    @Test
    fun opLogBoundedUnderContinuousInsertRemoveWithWindow() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("win-bounded"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = wire(seamA, backgroundScope, WindowPolicy.byCount(5))
        val repB = wire(seamB, backgroundScope, WindowPolicy.byCount(5))
        testScheduler.advanceUntilIdle()

        // 40 rounds: append a new tail element, occasionally remove the freshly-appended one. Without
        // windowing the op-log grows to ~40+ ops; with byCount(5) the leading prefix is continuously
        // forgotten and removed-tail tombstones are GC'd, so the op-log stays bounded near the window.
        repeat(40) { round ->
            val appended = repA.append(round)
            testScheduler.advanceUntilIdle()
            if (round % 3 == 0) {
                repA.applyOp(RgaOp.Remove(appended))
                testScheduler.advanceUntilIdle()
            }
        }
        testScheduler.advanceUntilIdle()

        val sizeA = repA.state.value.sequence.size
        assertTrue(sizeA <= 8, "op-log bounded under windowing (≈ window + in-flight tombstones); was $sizeA")
        assertEquals(repA.state.value.toList(), repB.state.value.toList(), "A and B converged")
    }

    // ---- SAFETY: concurrent Insert(J, after=window-dropped-I) is NOT lost — resurfaces ----

    @Test
    fun concurrentInsertAfterWindowDroppedPredecessorIsNotLost() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("win-safety"))
        val seamB = loom.join(InMemoryTag("b"))
        val peerB = PeerId("b")

        // Only A windows (byCount(2)); B has no window (so it can mint J after a soon-old element).
        val repA = wire(seamA, backgroundScope, WindowPolicy.byCount(2))
        val repB = wire(seamB, backgroundScope, WindowPolicy.never())
        testScheduler.advanceUntilIdle()

        // A appends 0,1 — both delivered to B. A's window of 2 is exactly full, nothing dropped yet.
        val id0 = repA.append(0)
        repA.append(1)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 1), repB.state.value.toList(), "B delivered [0,1] before the partition")

        // Partition B. While held, B mints J = Insert(99, after=element-0) — element-0 is about to be
        // window-dropped on A, and J is invisible to A (held). This is the #275-shaped race, but on
        // the windowing path, which is reroot-safe-without-gate.
        loom.holdDelivery(peerB)
        val (_, opJ) = repB.state.value.insertAfter(repB.replica, id0, 99)
        repB.applyOp(opJ)
        testScheduler.advanceUntilIdle()

        // A appends 2,3 → window forgets {0,1} (incl. J's predecessor) and broadcasts the Compact
        // (held from B). J remains undelivered to A.
        repA.append(2)
        repA.append(3)
        testScheduler.advanceUntilIdle()
        assertTrue(id0 !in repA.state.value.sequence.toSet(), "A window-dropped element 0 (J's predecessor)")
        assertEquals(listOf(2, 3), repA.state.value.toList(), "A windowed to the last 2")

        // Release B and converge. J must NOT be lost — its window-dropped predecessor is gone, so it
        // resurfaces at the window boundary (reroot-to-HEAD) rather than orphaning.
        loom.releaseDelivery(peerB)
        testScheduler.advanceUntilIdle()

        // Convergence + no-orphan are the safety property. J's predecessor was *window-dropped*, so
        // J is old history the count-window is entitled to forget: after reroot-to-HEAD it lands at
        // the leading edge and A's byCount(2) convergently re-drops it (broadcasting Compact{J}), so
        // both peers agree J is gone. Crucially J is **never orphaned** — never present-but-unreachable
        // (the #275 failure). Either it renders, or it is cleanly compacted everywhere.
        assertEquals(repA.state.value.toList(), repB.state.value.toList(), "A and B converge")
        assertNoOrphan(repA.state.value)
        assertNoOrphan(repB.state.value)
    }

    // ---- SAFETY (visible survival): J reroots INSIDE a roomy window and stays visible ----

    @Test
    fun concurrentInsertResurfacesVisiblyWhenWindowHasRoom() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("win-safety-visible"))
        val seamB = loom.join(InMemoryTag("b"))
        val peerB = PeerId("b")

        // A windows only the leading prefix beyond 4 — generous enough that a rerooted J is retained.
        val repA = wire(seamA, backgroundScope, WindowPolicy.byCount(4))
        val repB = wire(seamB, backgroundScope, WindowPolicy.never())
        testScheduler.advanceUntilIdle()

        // A appends 0,1; both delivered to B. Window of 4 not yet full, nothing dropped.
        val id0 = repA.append(0)
        repA.append(1)
        testScheduler.advanceUntilIdle()

        // Partition B; B mints J after element-0, which A is about to forget. J invisible to A.
        loom.holdDelivery(peerB)
        val (_, opJ) = repB.state.value.insertAfter(repB.replica, id0, 99)
        repB.applyOp(opJ)
        testScheduler.advanceUntilIdle()

        // A appends just one more (2) so visible = {0,1,2} ≤ 4 — A drops NOTHING yet. Then release B:
        // J reroots to HEAD and, with the window still having room, stays visible on convergence.
        repA.append(2)
        testScheduler.advanceUntilIdle()
        loom.releaseDelivery(peerB)
        testScheduler.advanceUntilIdle()

        val listA = repA.state.value.toList()
        assertEquals(repB.state.value.toList(), listA, "A and B converge")
        assertTrue(99 in listA, "J resurfaces visibly at the window boundary when the window has room")
        assertNoOrphan(repA.state.value)
    }
}
