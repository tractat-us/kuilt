package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Magnitudes spread over five decades, so `⌈log_γ v⌉` puts them in five clearly distinct log
 * buckets at the default α. Deliberately *not* adjacent: two magnitudes a rounding error apart
 * would share a bucket, and a one-key store has exactly one iteration order.
 */
private val MAGNITUDES = listOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0)

/** Below [DDSketch.DEFAULT_MIN_INDEXED_VALUE] — counts as a zero rather than reaching a bucket. */
private const val BELOW_ZERO_THRESHOLD = 1e-12

/** Above [DDSketch.DEFAULT_MAX_INDEXED_VALUE] — clamps into the top bucket and bumps `overflows`. */
private const val ABOVE_TOP_CLAMP = 1e19

/**
 * `DDSketch` — log-bucket sketch of [us.tractat.kuilt.crdt.GCounter] cells — bound here because
 * until #1979 it had **no** convergence binding, so nothing held its `positive` and `negative`
 * `Map<Int, GCounter>` stores to the byte law.
 *
 * ## What the knobs switch off
 *
 * - **[MAGNITUDES] spans five decades.** This is the knob that can switch the property off without
 *   looking like it: every value in one bucket makes both stores singletons, and a singleton map
 *   has exactly one iteration order, so the byte law cannot fail on any input. The bucket index is
 *   `⌈ln v / ln γ⌉`, so *decades* rather than small increments is what guarantees distinct keys
 *   whatever α is later changed to.
 * - **`add-negative` exists at all.** `positive` and `negative` are two independent non-canonical
 *   maps. An all-positive alphabet leaves `negative` empty on every state, so half the fix would be
 *   encoded as an empty map and never tested.
 * - **`add-zero` and `add-overflow`.** The two branches of [DDSketch.add] that bypass the bucket
 *   stores entirely, writing the `zeros` and `overflows` counters instead. They do not feed the
 *   byte law's map ordering — they are here so the pool covers the whole state rather than the two
 *   fields under repair, which is what stops this binding narrowing to a regression test for #1979.
 * - **`replicaCount = 3`, `opsPerReplica = 8`.** One replica gives no fold order at all, two give
 *   two, three give six.
 *
 * **A green `jvmTest` is not evidence of canonicality here** — see [LatticeLawHarness]'s own note
 * on why. Verify on `macosArm64Test` or `wasmJsTest`.
 */
internal class DDSketchConvergenceTest : LatticeLawSuite<DDSketch>() {
    override fun newHarness(): LatticeLawHarness<DDSketch> = LatticeLawHarness(
        initial = DDSketch.empty(),
        // Grow-only: every cell is a `GCounter` and `add` only ever increments one, so no op takes
        // an observation back and `defaultCriticalShapes` yields none.
        alphabet = listOf(
            LatticeOp("add-positive", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.add(replica, MAGNITUDES[random.nextInt(MAGNITUDES.size)]))
            },
            LatticeOp("add-negative", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.add(replica, -MAGNITUDES[random.nextInt(MAGNITUDES.size)]))
            },
            LatticeOp("add-zero", OpKind.ASSERT) { state, replicaIndex, _ ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.add(replica, BELOW_ZERO_THRESHOLD))
            },
            LatticeOp("add-overflow", OpKind.ASSERT) { state, replicaIndex, _ ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.add(replica, ABOVE_TOP_CLAMP))
            },
        ),
        // No RETIRE op, for the reason argued above — an added value is never un-added.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = DDSketch.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )

    /**
     * **Rig receipt: every magnitude reaches its own bucket, in both stores.**
     *
     * The byte law can only fail where a store holds more than one key, so [MAGNITUDES] spanning
     * distinct buckets is the fixture's load-bearing property — and it is a *computed* one
     * (`⌈ln v / ln γ⌉`), which makes it exactly the sort of thing a later edit to α, or to the
     * magnitudes, can collapse without anyone noticing. A collapse reds here rather than turning
     * five green tests vacuous.
     */
    @Test
    fun everyMagnitudeReachesItsOwnBucket() {
        val empty = newHarness().initial
        val replica = ReplicaId("R0")
        val positive = MAGNITUDES.map { empty.piece(empty.add(replica, it)).positiveBuckets.keys.single() }
        val negative = MAGNITUDES.map { empty.piece(empty.add(replica, -it)).negativeBuckets.keys.single() }
        assertEquals(
            MAGNITUDES.size, positive.toSet().size,
            "the magnitudes must land in DISTINCT positive buckets — a one-key store has exactly " +
                "one iteration order and makes the byte law unfalsifiable: $positive",
        )
        assertEquals(
            MAGNITUDES.size, negative.toSet().size,
            "and in distinct negative buckets — the mirrored store is a second, independent " +
                "non-canonical map: $negative",
        )
    }
}
