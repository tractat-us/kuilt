package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlin.random.Random
import kotlin.test.Test
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.MovableTree
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.VersionVector

/**
 * THROWAWAY measuring instrument for #2019. Not a check; deleted before the fix lands.
 *
 * Run with:
 * ```
 * ./gradlew :kuilt-conformance:jvmTest --tests "*CompactionReachProbe*" -Plattice.vacuity.breakdown=true
 * ```
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalStdlibApi::class)
internal class CompactionReachProbe {

    private val cbor = Cbor {}

    private class Binding<S : Quilted<S>>(
        val name: String,
        val harness: LatticeLawHarness<S>,
        val compactOnce: (S, VersionVector, VersionVector, VersionVector) -> Pair<S, Int>?,
    )

    @Test
    fun report() {
        if (System.getProperty("lattice.vacuity.breakdown") == null) return
        measure(
            Binding("Rga", RgaConvergenceTest().newHarness()) { s, sc, fm, d ->
                s.compact(sc, fm, d)?.let { (state, op) -> state to op.positions.size }
            },
        )
        measure(
            Binding("Fugue", FugueConvergenceTest().newHarness()) { s, sc, fm, d ->
                s.compact(sc, fm, d)?.let { (state, op) -> state to op.positions.size }
            },
        )
        measure(
            Binding("MovableTree", MovableTreeConvergenceTest().newHarness()) { s, sc, fm, d ->
                s.compact(sc, fm, d)?.let { (state, op) -> state to op.droppedDots.size }
            },
        )
        for (ops in listOf(12, 16, 24)) {
            val base = FugueConvergenceTest().newHarness()
            measure(
                Binding(
                    "Fugue@ops=$ops",
                    LatticeLawHarness(
                        initial = base.initial,
                        alphabet = base.alphabet,
                        serializer = base.serializer,
                        criticalShapes = base.criticalShapes,
                        floors = base.floors,
                        replicaCount = base.replicaCount,
                        opsPerReplica = ops,
                    ),
                ) { s, sc, fm, d -> s.compact(sc, fm, d)?.let { (state, op) -> state to op.positions.size } },
            )
            val mtBase = MovableTreeConvergenceTest().newHarness()
            measure(
                Binding(
                    "MovableTree@ops=$ops",
                    LatticeLawHarness(
                        initial = mtBase.initial,
                        alphabet = mtBase.alphabet,
                        serializer = mtBase.serializer,
                        criticalShapes = mtBase.criticalShapes,
                        floors = mtBase.floors,
                        replicaCount = mtBase.replicaCount,
                        opsPerReplica = ops,
                    ),
                ) { s, sc, fm, d -> s.compact(sc, fm, d)?.let { (state, op) -> state to op.droppedDots.size } },
            )
        }
    }

    private fun <S : Quilted<S>> measure(binding: Binding<S>) {
        val h = binding.harness
        var postMergeSeeds = 0
        var postMergeMaxDropped = 0
        var preMergeTwoOrMore = 0
        var preMergeAnySeeds = 0
        var preMergeMaxDropped = 0
        var postMergeByteMismatch = 0
        var preMergeByteMismatch = 0
        var postMergeValueMismatch = 0
        var preMergeValueMismatch = 0
        var postMergeOrderVarying = 0

        for (seed in 0L..31L) {
            val replicas = buildReplicas(h, Random(seed))

            // ── Phase A: fold every permutation, THEN compact to stable.
            val encodings = mutableListOf<ByteArray>()
            val states = mutableListOf<S>()
            var maxDroppedThisSeed = 0
            var anyCompacted = false
            for (perm in permutationsOf(replicas.indices.toList())) {
                val folded = perm.fold(h.initial) { acc, i -> acc.piece(replicas[i]) }
                val (compacted, steps, maxDropped) = compactToStable(binding, folded)
                if (steps > 0) anyCompacted = true
                if (maxDropped > maxDroppedThisSeed) maxDroppedThisSeed = maxDropped
                states += compacted
                encodings += encode(h.serializer, compacted)
            }
            if (anyCompacted) postMergeSeeds++
            if (maxDroppedThisSeed > postMergeMaxDropped) postMergeMaxDropped = maxDroppedThisSeed
            if (states.any { it != states[0] }) postMergeValueMismatch++
            if (encodings.any { !it.contentEquals(encodings[0]) }) postMergeByteMismatch++

            // Did the freshly-minted Compact record's own iteration order vary across folds?
            if (compactRecordOrderVaries(binding, replicas, h)) postMergeOrderVarying++

            // ── Phase B: compact each replica ALONE to stable, THEN fold every permutation.
            var replicasThatCompacted = 0
            val preCompacted = replicas.map { r ->
                val (compacted, steps, maxDropped) = compactToStable(binding, r)
                if (steps > 0) replicasThatCompacted++
                if (maxDropped > preMergeMaxDropped) preMergeMaxDropped = maxDropped
                compacted
            }
            if (replicasThatCompacted >= 2) preMergeTwoOrMore++
            if (replicasThatCompacted >= 1) preMergeAnySeeds++
            val bStates = mutableListOf<S>()
            val bEncodings = mutableListOf<ByteArray>()
            for (perm in permutationsOf(preCompacted.indices.toList())) {
                val folded = perm.fold(h.initial) { acc, i -> acc.piece(preCompacted[i]) }
                bStates += folded
                bEncodings += encode(h.serializer, folded)
            }
            if (bStates.any { it != bStates[0] }) preMergeValueMismatch++
            if (bEncodings.any { !it.contentEquals(bEncodings[0]) }) preMergeByteMismatch++
        }

        println(
            """
            |=== ${binding.name} (opsPerReplica=${h.opsPerReplica}, replicaCount=${h.replicaCount}, seeds 0..31)
            |  PHASE A (post-merge)  seeds reaching a state-changing compact : $postMergeSeeds / 32
            |  PHASE A               max ids dropped in one step             : $postMergeMaxDropped
            |  PHASE A               seeds where the minted Compact's own order varies across folds : $postMergeOrderVarying / 32
            |  PHASE A               seeds with a VALUE mismatch across folds: $postMergeValueMismatch / 32
            |  PHASE A               seeds with a BYTE  mismatch across folds: $postMergeByteMismatch / 32
            |  PHASE B (pre-merge)   seeds with >=1 replica compacting       : $preMergeAnySeeds / 32
            |  PHASE B               seeds with >=2 replicas compacting      : $preMergeTwoOrMore / 32
            |  PHASE B               max ids dropped in one step             : $preMergeMaxDropped
            |  PHASE B               seeds with a VALUE mismatch across folds: $preMergeValueMismatch / 32
            |  PHASE B               seeds with a BYTE  mismatch across folds: $preMergeByteMismatch / 32
            """.trimMargin(),
        )
    }

    /**
     * Does the *freshly minted* `Compact` op differ in raw iteration order between two folds?
     * This is the measurement that killed the post-merge-only design in #2045.
     */
    private fun <S : Quilted<S>> compactRecordOrderVaries(
        binding: Binding<S>,
        replicas: List<S>,
        h: LatticeLawHarness<S>,
    ): Boolean {
        val orders = permutationsOf(replicas.indices.toList()).map { perm ->
            val folded = perm.fold(h.initial) { acc, i -> acc.piece(replicas[i]) }
            rawCompactOrder(binding, folded)
        }
        return orders.any { it != orders[0] }
    }

    private fun <S : Quilted<S>> rawCompactOrder(binding: Binding<S>, state: S): List<String> {
        val cut = cutOf(state)
        return when (state) {
            is Rga<*> -> state.compact(cut, cut, cut)?.second?.positions?.keys?.map { it.toString() }.orEmpty()
            is Fugue<*> -> state.compact(cut, cut, cut)?.second?.positions?.keys?.map { it.toString() }.orEmpty()
            is MovableTree<*> -> state.compact(cut, cut, cut)?.second?.droppedDots?.map { it.toString() }.orEmpty()
            else -> emptyList()
        }.also { require(binding.name.isNotEmpty()) }
    }

    private data class Stable<S>(val state: S, val steps: Int, val maxDropped: Int)

    private fun <S : Quilted<S>> compactToStable(binding: Binding<S>, from: S): Stable<S> {
        var state = from
        var steps = 0
        var maxDropped = 0
        while (true) {
            val cut = cutOf(state)
            val step = binding.compactOnce(state, cut, cut, cut) ?: break
            state = step.first
            if (step.second > maxDropped) maxDropped = step.second
            steps++
            if (steps > COMPACT_STEP_CAP) break
        }
        return Stable(state, steps, maxDropped)
    }

    private fun cutOf(state: Quilted<*>): VersionVector =
        contiguousFrontierCopy(state.causalDots(), state.causalFloor())

    private fun <S : Quilted<S>> buildReplicas(h: LatticeLawHarness<S>, random: Random): List<S> =
        List(h.replicaCount) { r ->
            (0 until h.opsPerReplica).fold(h.initial) { acc, _ -> h.gen.applyRandomOp(acc, r, random) }
        }

    private fun <S> encode(serializer: KSerializer<S>, state: S): ByteArray =
        cbor.encodeToByteArray(serializer, state)

    private fun <T> permutationsOf(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.flatMapIndexed { i, head ->
            val rest = items.toMutableList().also { it.removeAt(i) }
            permutationsOf(rest).map { listOf(head) + it }
        }
    }

    /** Local copy of `Quilter.contiguousFrontier`, which is `internal` to a module this one cannot see. */
    private fun contiguousFrontierCopy(dots: Set<Dot>, floor: VersionVector): VersionVector {
        val seqsByAuthor: Map<ReplicaId, Set<Long>> = dots
            .groupBy(keySelector = { it.replica }, valueTransform = { it.seq })
            .mapValues { (_, seqs) -> seqs.toSet() }
        val authors = seqsByAuthor.keys + floor.entries.keys
        val highWaters = authors.associateWith { author ->
            var n = floor[author]
            val seqs = seqsByAuthor[author].orEmpty()
            while ((n + 1L) in seqs) n++
            n
        }
        return VersionVector.of(highWaters)
    }

    private companion object {
        const val COMPACT_STEP_CAP = 64
    }
}
