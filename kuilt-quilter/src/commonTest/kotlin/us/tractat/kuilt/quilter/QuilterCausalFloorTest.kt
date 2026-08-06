/**
 * [contiguousFrontier] over a CRDT that carries a **compaction floor** (#2127).
 *
 * A raised [us.tractat.kuilt.crdt.Rga.compactedBelow] purges its ops and — unlike an
 * `RgaOp.Compact` — records no id set, so those dots leave
 * [us.tractat.kuilt.crdt.Quilted.causalDots]. Counting the contiguous frontier from `0`
 * would stop at the first swallowed seq, and because the floor is downward-closed that seq
 * is `1` — so the author's delivered high-water collapses to `0` and every downstream GC
 * stalls forever. Starting each author's walk at
 * [us.tractat.kuilt.crdt.Quilted.causalFloor] bridges exactly the dots the floor asserts
 * were delivered.
 *
 * The last test drives the **live replicator**, not the helper: it is what pins the call
 * site actually passing the floor. Every helper-level test here would pass just as well
 * against a `Quilter` that still called `contiguousFrontier(dots, VersionVector.EMPTY)`.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class QuilterCausalFloorTest {

    private val a = ReplicaId("a")

    @Test
    fun aFrontierWithNoFloorCountsFromOneAsBefore() {
        val dots = setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L))
        assertEquals(VersionVector.of(mapOf(a to 3L)), contiguousFrontier(dots, VersionVector.EMPTY))
    }

    @Test
    fun aFloorBridgesTheDotsItSwallowed() {
        // The replica delivered 1..5 and floored 1..3, so only 4 and 5 remain as dots.
        val dots = setOf(Dot(a, 4L), Dot(a, 5L))
        val floor = VersionVector.of(mapOf(a to 3L))

        assertEquals(
            VersionVector.of(mapOf(a to 5L)),
            contiguousFrontier(dots, floor),
            "without the floor the walk stops at 0 and stalls every downstream GC",
        )
    }

    @Test
    fun aGapAboveTheFloorStillStopsTheWalk() {
        val dots = setOf(Dot(a, 4L), Dot(a, 6L))
        val floor = VersionVector.of(mapOf(a to 3L))

        assertEquals(VersionVector.of(mapOf(a to 4L)), contiguousFrontier(dots, floor), "5 is genuinely missing")
    }

    @Test
    fun anAuthorPresentOnlyInTheFloorStillReportsAFrontier() {
        assertEquals(
            VersionVector.of(mapOf(a to 3L)),
            contiguousFrontier(emptySet(), VersionVector.of(mapOf(a to 3L))),
            "a fully-drained window is still a delivered frontier",
        )
    }

    /**
     * The floor overlaps [us.tractat.kuilt.crdt.Quilted.causalDots] rather than partitioning
     * it — `dropWindow`'s contiguity walk steps over an own dot a retained `Compact` still
     * re-emits — so a dot at or below the floor may also arrive in the dot set. Re-reading it
     * must be a no-op, never a second start point.
     */
    @Test
    fun aDotAlsoPresentBelowTheFloorChangesNothing() {
        val floor = VersionVector.of(mapOf(a to 3L))
        assertAll(
            { assertEquals(VersionVector.of(mapOf(a to 3L)), contiguousFrontier(setOf(Dot(a, 2L)), floor)) },
            {
                assertEquals(
                    VersionVector.of(mapOf(a to 4L)),
                    contiguousFrontier(setOf(Dot(a, 2L), Dot(a, 4L)), floor),
                )
            },
        )
    }

    /**
     * The walk is O(dots above the floor), not O(floor). A correctness test alone would pass
     * against a Θ(floor) enumeration, so the shape is measured directly: ten million swallowed
     * seqs must cost nothing to step over.
     *
     * **A hang here is a stop-and-fix signal, not a reason to shrink the constant** — it means
     * the walk is enumerating the very dots the floor exists to skip.
     */
    @Test
    fun aHugeFloorCostsNothingToWalk() {
        val floor = VersionVector.of(mapOf(a to 10_000_000L))
        val dots = setOf(Dot(a, 10_000_001L))

        assertEquals(VersionVector.of(mapOf(a to 10_000_001L)), contiguousFrontier(dots, floor))
    }

    /**
     * The wiring test: a real [Quilter] over a windowed [Rga].
     *
     * `dropWindow` folds seqs 1 and 2 into the floor, so `causalDots()` afterwards holds only
     * `(a,3)`. A frontier walk that started at `0` would report `0` for `a` — a *regression*
     * from the 3 it had a moment earlier — which is then gossiped, and `compact`'s condition 3
     * (`delivered.dominates(frontierMax)`) can never be satisfied again.
     */
    @Test
    fun aWindowedRgaUnderAQuilterKeepsItsDeliveredFrontier() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("floor-frontier"))
        loom.join(InMemoryTag("b"))
        val replicator = Quilter(
            replica = a,
            seam = seam,
            initial = Rga.empty<String>(),
            messageSerializer = QuiltMessage.serializer(Rga.wireSerializer(serializer<String>())),
            scope = backgroundScope,
            config = QuilterConfig(expectVirtualTime = true),
        )

        val ids = List(3) { i ->
            val (_, op) = replicator.state.value.insertAfter(a, RgaId.HEAD, "v$i")
            replicator.apply(Patch(Rga.empty<String>().apply(op)))
            op.id
        }
        val beforeDrop = replicator.deliveredLocal.value[a]

        replicator.mutate { rga -> rga.dropWindow(a, setOf(ids[0], ids[1]))!!.second }

        assertAll(
            { assertEquals(3L, beforeDrop, "the probe is vacuous unless the frontier reached 3 first") },
            {
                assertEquals(
                    VersionVector.of(mapOf(a to 2L)),
                    replicator.state.value.causalFloor(),
                    "the probe is vacuous unless dropWindow actually folded both seqs into the floor",
                )
            },
            {
                assertEquals(
                    setOf(Dot(a, 3L)),
                    replicator.state.value.causalDots(),
                    "the probe is vacuous unless the floored dots really left causalDots()",
                )
            },
            {
                assertEquals(
                    3L,
                    replicator.deliveredLocal.value[a],
                    "delivered[a] must not regress when a window folds into the floor",
                )
            },
        )
    }
}
