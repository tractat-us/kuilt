package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Convergence stress-test for [JsonCrdt].
 *
 * Operations randomly mix:
 * - scalar puts (producing [JsonNode.Leaf])
 * - object puts (producing [JsonNode.Object])
 * - array puts (producing [JsonNode.Array]) — exercises cross-type conflicts
 * - key removals
 *
 * A fixed key pool forces concurrent type-changing and removal collisions across
 * replicas, exercising the full cross-type tiebreak under every delivery permutation.
 */
internal class JsonCrdtConvergenceTest : CrdtConvergenceSuite<JsonCrdt>() {

    private val replicaIds = List(3) { ReplicaId("R$it") }
    private val keys = listOf("k0", "k1", "k2")

    override fun newHarness(): CrdtConvergenceHarness<JsonCrdt> = CrdtConvergenceHarness(
        initial = JsonCrdt.empty(replicaIds[0]),
        gen = OperationGenerator { state, replicaIndex, random ->
            val r = replicaIds[replicaIndex]
            val key = keys[random.nextInt(keys.size)]
            when (random.nextInt(4)) {
                0 -> state.withReplica(r).set(key, leafNode(r, "v${random.nextInt(4)}"))
                1 -> state.withReplica(r).set(key, objNode(r, "f" to leafNode(r, "obj")))
                2 -> state.withReplica(r).set(key, arrNode(r, leafNode(r, "item")))
                else -> state.withReplica(r).remove(key)
            }
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )

    private fun leafNode(r: ReplicaId, v: String) =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(r, JsonValue.Str(v)))

    private fun objNode(r: ReplicaId, vararg pairs: Pair<String, JsonNode>): JsonNode.Object {
        val map = pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) -> acc.put(r, k, v) }
        return JsonNode.Object(map)
    }

    private fun arrNode(r: ReplicaId, vararg elements: JsonNode): JsonNode.Array {
        val rga = elements.foldIndexed(Rga.empty<JsonNode>()) { i, acc, elem ->
            val afterId = if (i == 0) RgaId.HEAD else acc.sequence.last()
            acc.insertAfter(r, afterId, elem).first
        }
        return JsonNode.Array(rga)
    }
}
