package us.tractat.kuilt.warp

import us.tractat.kuilt.conformance.lattice.LatticeLawHarness
import us.tractat.kuilt.conformance.lattice.LatticeLawSuite
import us.tractat.kuilt.conformance.lattice.LatticeOp
import us.tractat.kuilt.conformance.lattice.OpKind
import us.tractat.kuilt.conformance.lattice.VacuityFloors
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.piece

/** Sources that share one `String.hashCode`, plus one that does not. See the binding's KDoc. */
private val SOURCES: List<OpId> = listOf("Aa", "BB", "C#", "d").map(::OpId)

/**
 * [WarpStats] under the lattice and byte laws (#2715).
 *
 * ## What the knobs switch off
 *
 * - **[SOURCES].** The byte law can only fail where the map has more than one iteration order.
 *   `"Aa"`, `"BB"` and `"C#"` collide into one JVM `HashMap` bin, which keeps insertion order, so the
 *   fold order shows through on the JVM as well. Ordinary ids would leave this suite blind there.
 * - **HyperLogLog precision 4, through [WarpStats.of].** [WarpStats.observe] only ever seeds a
 *   default sketch, which encodes to about 12 KiB, element by element, and this suite encodes
 *   thousands of pool states per seed. The sketch's size has no bearing on the property under test,
 *   which is the order of the map around it.
 */
internal class WarpStatsConvergenceTest : LatticeLawSuite<WarpStats>() {
    override fun newHarness(): LatticeLawHarness<WarpStats> = LatticeLawHarness(
        initial = WarpStats.empty(),
        // Mirrors `observe`, whose delta is one source's sparse fragment, at the smaller precision.
        alphabet = listOf(
            LatticeOp("observe", OpKind.ASSERT) { state, _, random ->
                val source = SOURCES.random(random)
                val sketch = state.sketches()[source] ?: HyperLogLog.empty(precision = 4)
                state.piece(WarpStats.of(mapOf(source to sketch.piece(sketch.add("e${random.nextLong()}")))))
            },
        ),
        // No RETIRE op: the key set is add-only and every register only rises.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = WarpStats.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
