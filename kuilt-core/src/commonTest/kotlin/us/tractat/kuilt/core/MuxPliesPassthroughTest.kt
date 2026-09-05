@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.composite.CompositeLoom
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A mux channel view forwards its base seam's **per-ply breakdown** (#2393).
 *
 * `MuxBase.ChannelView` used to inherit [Seam.plies]' default, which synthesises a single
 * `{ PlyId.Sole: state }` entry from `state`. Over a multi-ply `CompositeSeam` that is silent
 * information loss: a consumer holding a channel view could not tell a three-ply composite from a
 * single-ply fabric, and nothing red — the contract invariant ("`state` equals the rollup of
 * `plies.values`") holds *trivially* for a one-entry map whose value **is** `state`. That is the
 * same shape as the capability defect fixed in #1546.
 *
 * A pass-through is the right answer, not a subtraction: the plies of the base *are* the transport
 * paths carrying the channel. Multiplexing changes how many logical sessions share a link; it
 * neither adds nor removes a path.
 *
 * ## Why the fixture is a genuinely multi-ply composite
 *
 * Two plies, not one. Over a single-ply composite "forwards the base's map" and "synthesises a
 * `Sole` entry" produce maps of the same *arity*, so only the key would differ, and a fixture that
 * happened to name its ply `sole` would agree outright. Two differently-named plies make the two
 * readings disagree on arity and on both keys, and every arm asserts that rig before its claim.
 */
class MuxPliesPassthroughTest {

    @Test
    fun aMuxSeamChannelViewReportsTheBasePerPlyBreakdown() = runTest {
        val base = multiPlyComposite(UnconfinedTestDispatcher(testScheduler))

        val view = MuxSeam(base, backgroundScope).channel(0x07.toByte())

        assertAll(
            { assertRigIsMultiPly(base) },
            {
                assertEquals(
                    base.plies.value,
                    view.plies.value,
                    "a MuxSeam channel view must report the base's per-ply breakdown, not a synthetic Sole entry",
                )
            },
            { assertEquals(PLY_IDS, view.plies.value.keys, "…keyed by the base's real PlyIds") },
        )

        base.close()
    }

    @Test
    fun aNamedMuxChannelViewReportsTheBasePerPlyBreakdown() = runTest {
        val base = multiPlyComposite(UnconfinedTestDispatcher(testScheduler))

        val view = NamedMux(base, backgroundScope).channel("telemetry")

        assertAll(
            { assertRigIsMultiPly(base) },
            {
                assertEquals(
                    base.plies.value,
                    view.plies.value,
                    "a NamedMux channel view must report the base's per-ply breakdown, not a synthetic Sole entry",
                )
            },
            { assertEquals(PLY_IDS, view.plies.value.keys, "…keyed by the base's real PlyIds") },
        )

        base.close()
    }

    /**
     * `MuxClientLoom`'s resumable handle sits above a channel view, so the breakdown has to survive
     * one more hop to reach the consumer that actually holds a `MuxClientLoom` seam. Without this the
     * transitive forwarding #2393 describes stops one layer short of the public entry point.
     */
    @Test
    fun aResumableChannelHandleReportsTheBasePerPlyBreakdown() = runTest {
        val base = multiPlyComposite(UnconfinedTestDispatcher(testScheduler))
        val loom = MuxClientLoom(
            base = FixedSeamLoom(base),
            baseRendezvous = Rendezvous.New(Pattern("base")),
            scope = backgroundScope,
            nameOf = { "telemetry" },
        )

        val handle = loom.host(Pattern("telemetry"))

        assertAll(
            { assertRigIsMultiPly(base) },
            {
                assertEquals(
                    base.plies.value,
                    handle.plies.value,
                    "a resumable channel handle must report the base's per-ply breakdown, not a synthetic Sole entry",
                )
            },
        )

        base.close()
    }

    /**
     * The rig, asserted rather than assumed: unless the base really carries more than one ply, and
     * none of them is [PlyId.Sole], the forwarded map and the synthesised one are indistinguishable.
     */
    private fun assertRigIsMultiPly(base: Seam) {
        assertTrue(
            base.plies.value.size > 1,
            "rig: a single-ply base cannot distinguish the forwarded breakdown from the synthetic Sole entry",
        )
        assertNotEquals(
            setOf(PlyId.Sole),
            base.plies.value.keys,
            "rig: a base whose ply is named Sole would agree with the synthetic entry by accident",
        )
    }

    /** A two-ply composite, woven and settled — the base whose breakdown must survive the mux. */
    private suspend fun multiPlyComposite(dispatcher: kotlin.coroutines.CoroutineContext): Seam {
        val seam = CompositeLoom(
            plies = PLY_IDS.map { it to (InMemoryLoom() as Loom) },
            dispatcher = dispatcher,
        ).host(Pattern("host"))
        seam.plies.first { it.keys == PLY_IDS }
        return seam
    }

    private companion object {
        val PLY_IDS = setOf(PlyId("wifi"), PlyId("relay"))
    }
}

/** A [Loom] handing back one fixed [seam], so [MuxClientLoom] has a base to wrap. */
private class FixedSeamLoom(private val seam: Seam) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = seam
}
