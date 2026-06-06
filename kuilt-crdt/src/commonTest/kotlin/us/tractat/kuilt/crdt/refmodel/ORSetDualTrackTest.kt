package us.tractat.kuilt.crdt.refmodel

import kotlin.random.Random
import kotlin.test.Test
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Dual-track test: runs [ORSet] (SUT) and [NaiveORSet] (reference model) in
 * lockstep across random operation sequences over 3 replicas, with random
 * pairwise merges interleaved with local add/remove ops. Asserts element
 * equality at every step after every operation.
 *
 * A divergence is a real bug — the assertion message captures seed + step.
 * Seeds 0..63 give broad coverage of concurrent add, remove, and merge ordering.
 */
class ORSetDualTrackTest {

    @Test
    fun sutMatchesReferenceModelAcrossSeeds() {
        for (seed in 0L..63L) {
            runScenario(seed)
        }
    }

    private fun runScenario(seed: Long) {
        val random = Random(seed)
        val replicaIndices = listOf(0, 1, 2)
        val ids = replicaIndices.map { ReplicaId("R$it") }
        val sut = replicaIndices.map { ORSet.empty<String>() }.toMutableList()
        val ref = replicaIndices.map { NaiveORSet.empty<String>() }.toMutableList()

        repeat(50) { step ->
            val r = replicaIndices.random(random)
            val rId = ids[r]
            when (random.nextInt(0, 3)) {
                0 -> applyAdd(sut, ref, r, rId, random)
                1 -> applyRemove(sut, ref, r, random)
                else -> applyMerge(sut, ref, r, replicaIndices, random)
            }
            assertAllReplicasMatch(sut, ref, replicaIndices, seed, step)
        }
    }

    private fun applyAdd(
        sut: MutableList<ORSet<String>>,
        ref: MutableList<NaiveORSet<String>>,
        r: Int,
        rId: ReplicaId,
        random: Random,
    ) {
        val e = "e-${random.nextInt(0, 4)}"
        sut[r] = sut[r].add(rId, e)
        ref[r] = ref[r].add(rId, e)
    }

    private fun applyRemove(
        sut: MutableList<ORSet<String>>,
        ref: MutableList<NaiveORSet<String>>,
        r: Int,
        random: Random,
    ) {
        val candidates = (sut[r].elements + ref[r].elements).toList()
        if (candidates.isEmpty()) return
        val e = candidates.random(random)
        sut[r] = sut[r].remove(e)
        ref[r] = ref[r].remove(e)
    }

    private fun applyMerge(
        sut: MutableList<ORSet<String>>,
        ref: MutableList<NaiveORSet<String>>,
        r: Int,
        replicaIndices: List<Int>,
        random: Random,
    ) {
        val o = replicaIndices.filter { it != r }.random(random)
        sut[r] = sut[r].piece(sut[o])
        ref[r] = ref[r].merge(ref[o])
    }

    private fun assertAllReplicasMatch(
        sut: List<ORSet<String>>,
        ref: List<NaiveORSet<String>>,
        replicaIndices: List<Int>,
        seed: Long,
        step: Int,
    ) {
        for (i in replicaIndices) {
            check(sut[i].elements == ref[i].elements) {
                "Divergence at seed=$seed step=$step replica=$i:\n" +
                    "  SUT.elements = ${sut[i].elements}\n" +
                    "  REF.elements = ${ref[i].elements}"
            }
        }
    }
}
