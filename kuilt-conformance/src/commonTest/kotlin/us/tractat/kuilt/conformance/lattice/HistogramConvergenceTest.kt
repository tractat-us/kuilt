package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.Histogram
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.test.Test
import kotlin.test.assertEquals

/** Seventeen upper bounds — eighteen buckets, so the `buckets` map has room to hold many keys. */
private val BOUNDARIES: List<Double> = List(17) { (it + 1) * 10.0 }

/** The number of buckets [BOUNDARIES] defines: `N` bounds make `N + 1` buckets. */
private const val BUCKET_COUNT = 18

/** Bucket `i`'s midpoint, for a value guaranteed to land in it rather than on a boundary. */
private fun inBucket(index: Int): Double = 5.0 + index * 10.0

/**
 * `Histogram` — explicit-bucket histogram of [us.tractat.kuilt.crdt.GCounter] cells — bound here
 * because until #1979 it had **no** convergence binding, so nothing held its
 * `buckets: Map<Int, GCounter>` to the byte law.
 *
 * ## What the knobs switch off
 *
 * The byte law compares two fold orders of the same operands and can only fail where the encoding
 * has more than one order to choose between. For this type that means the `buckets` map must hold
 * **more than one key**, and the numbers below are what make that reachable on every seed:
 *
 * - **`BOUNDARIES.size = 17` (18 buckets).** This is the knob that can silently switch the whole
 *   property off. `Histogram.empty(emptyList())` is a legal, documented configuration — one
 *   catch-all bucket — and on it `buckets` is a singleton on every reachable state, has exactly one
 *   iteration order, and the byte law cannot fail on any input at all. So is any alphabet whose
 *   values all fall in one bucket, however many bounds are declared.
 * - **`record-own-bucket` spreads by `replicaIndex`.** Three replicas start in three *different*
 *   buckets, so the merged key order is a function of the fold order from the first gossip on,
 *   rather than only once random exploration happens to have spread them.
 * - **`record-roam` covers all 18.** Two replicas that land on the *same* bucket exercise
 *   `mergeValues`' combine branch — a `GCounter` join inside a map cell — which the pinned op alone
 *   never reaches, since it only ever unions disjoint keys.
 * - **`record-negative`.** [Histogram] carries its running total as *two* `GCounterDouble`s, and an
 *   all-positive alphabet leaves `negativeSum` at zero on every state — so the second of them would
 *   be encoded empty and its own map order never tested. This op is the only thing that populates
 *   it.
 * - **`replicaCount = 3`, `opsPerReplica = 8`.** As elsewhere: one replica gives no fold order at
 *   all, two give two, three give six.
 *
 * **A green `jvmTest` is not evidence of canonicality here** — see [LatticeLawHarness]'s own note
 * on why. Verify on `macosArm64Test` or `wasmJsTest`.
 */
internal class HistogramConvergenceTest : LatticeLawSuite<Histogram>() {
    override fun newHarness(): LatticeLawHarness<Histogram> = LatticeLawHarness(
        initial = Histogram.empty(BOUNDARIES),
        // Grow-only: every cell is a `GCounter` and `record` only ever increments one, so no op
        // takes an observation back and `defaultCriticalShapes` yields none.
        alphabet = listOf(
            LatticeOp("record-own-bucket", OpKind.ASSERT) { state, replicaIndex, _ ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.record(replica, inBucket(replicaIndex * 6)))
            },
            LatticeOp("record-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.record(replica, inBucket(random.nextInt(BUCKET_COUNT))))
            },
            LatticeOp("record-negative", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.record(replica, -inBucket(random.nextInt(BUCKET_COUNT))))
            },
        ),
        // No RETIRE op, for the reason argued above — a recorded value is never un-recorded.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = Histogram.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )

    /**
     * **Rig receipt: the bucket space is wide, and the alphabet actually spreads across it.**
     *
     * The byte law this binding exists for can only fail where the `buckets` map holds more than
     * one key, so the fixture's width is load-bearing and this asserts it rather than leaving it
     * chosen. `Histogram.empty(emptyList())` is a legal configuration on which the property is
     * unfalsifiable on every input; so is any alphabet whose values all land in one bucket. A
     * future edit that shrinks [BOUNDARIES], or collapses `record-own-bucket`'s spread, reds here
     * instead of quietly turning five green tests into five vacuous ones.
     *
     * Read off the harness rather than restated, so the receipt cannot drift from the binding.
     */
    @Test
    fun theAlphabetSpreadsAcrossDistinctBuckets() {
        val harness = newHarness()
        val empty = harness.initial
        val firstPopulated = (0 until harness.replicaCount).map { replicaIndex ->
            empty.piece(empty.record(ReplicaId("R$replicaIndex"), inBucket(replicaIndex * 6)))
                .bucketCounts.indexOfFirst { it > 0L }
        }
        assertEquals(
            BUCKET_COUNT, empty.bucketCounts.size,
            "the bucket space must stay wide — a one-bucket histogram makes the byte law " +
                "unfalsifiable on every input",
        )
        assertEquals(
            harness.replicaCount, firstPopulated.toSet().size,
            "each replica's pinned op must land in a DIFFERENT bucket, else the merged key order " +
                "is not a function of the fold order: $firstPopulated",
        )
    }
}
