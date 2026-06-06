package us.tractat.kuilt.crdt.refmodel

import kotlin.random.Random
import kotlin.test.Test
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * Dual-track test: runs [ORMap] (SUT) and [NaiveORMap] (reference model) in
 * lockstep across random operation sequences over 3 replicas, with random
 * pairwise merges interleaved with local put/remove ops. Asserts key-set and
 * per-key GCounter value equality at every step after every operation.
 *
 * Uses [ORMap]<String, [GCounter]> as the SUT with [NaiveORMap]<String, [GCounter]>
 * as the reference. GCounter per-replica increments are kept replica-local (each
 * replica only bumps its own slot) so GCounter.piece (elementwise max) is correct.
 *
 * A divergence is a real bug — the assertion captures seed + step + key detail.
 */
class ORMapDualTrackTest {

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
        val sut = replicaIndices.map { ORMap.empty<String, GCounter>() }.toMutableList()
        val ref = replicaIndices.map { NaiveORMap.empty<String, GCounter>() }.toMutableList()

        repeat(50) { step ->
            val r = replicaIndices.random(random)
            val rId = ids[r]
            when (random.nextInt(0, 3)) {
                0 -> applyPut(sut, ref, r, rId, random)
                1 -> applyRemove(sut, ref, r, random)
                else -> applyMerge(sut, ref, r, replicaIndices, random)
            }
            assertAllReplicasMatch(sut, ref, replicaIndices, seed, step)
        }
    }

    private fun applyPut(
        sut: MutableList<ORMap<String, GCounter>>,
        ref: MutableList<NaiveORMap<String, GCounter>>,
        r: Int,
        rId: ReplicaId,
        random: Random,
    ) {
        val key = "k-${random.nextInt(0, 3)}"
        val increment = random.nextLong(1L, 4L)
        // Each replica only touches its own GCounter slot — avoids slot-ownership violations.
        val delta = GCounter.of(rId to ((sut[r][key]?.count(rId) ?: 0L) + increment))
        sut[r] = sut[r].put(rId, key, delta)
        ref[r] = ref[r].put(rId, key, delta)
    }

    private fun applyRemove(
        sut: MutableList<ORMap<String, GCounter>>,
        ref: MutableList<NaiveORMap<String, GCounter>>,
        r: Int,
        random: Random,
    ) {
        val candidates = (sut[r].keys + ref[r].keys).toList()
        if (candidates.isEmpty()) return
        val key = candidates.random(random)
        sut[r] = sut[r].remove(key)
        ref[r] = ref[r].remove(key)
    }

    private fun applyMerge(
        sut: MutableList<ORMap<String, GCounter>>,
        ref: MutableList<NaiveORMap<String, GCounter>>,
        r: Int,
        replicaIndices: List<Int>,
        random: Random,
    ) {
        val o = replicaIndices.filter { it != r }.random(random)
        sut[r] = sut[r].piece(sut[o])
        ref[r] = ref[r].merge(ref[o])
    }

    private fun assertAllReplicasMatch(
        sut: List<ORMap<String, GCounter>>,
        ref: List<NaiveORMap<String, GCounter>>,
        replicaIndices: List<Int>,
        seed: Long,
        step: Int,
    ) {
        for (i in replicaIndices) {
            val sutKeys = sut[i].keys
            val refKeys = ref[i].keys
            check(sutKeys == refKeys) {
                "Key-set divergence at seed=$seed step=$step replica=$i:\n" +
                    "  SUT.keys = $sutKeys\n" +
                    "  REF.keys = $refKeys"
            }
            for (k in sutKeys) {
                val sutVal = sut[i][k]?.value
                val refVal = ref[i][k]?.value
                check(sutVal == refVal) {
                    "Value divergence at seed=$seed step=$step replica=$i key=$k:\n" +
                        "  SUT[$k].value = $sutVal\n" +
                        "  REF[$k].value = $refVal"
                }
            }
        }
    }
}
