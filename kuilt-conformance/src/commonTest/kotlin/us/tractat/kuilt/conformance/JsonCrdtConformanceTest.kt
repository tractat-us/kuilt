package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice-law conformance for [JsonCrdt].
 *
 * Each sample uses a distinct [ReplicaId] so the ORMap dot spaces are fully
 * independent — samples that share a replica accidentally erase each other's keys
 * when merged (the shared-replica's context observes the other sample's dots as
 * "delivered without the key", triggering a causal remove). Using one replica per
 * sample prevents this causal aliasing while still exercising the cross-type
 * conflict rules.
 *
 * Samples include:
 * - empty document
 * - scalar leaf at "k" (cross-type conflict pair with the object sample)
 * - nested object at "k" (cross-type conflict pair with the leaf and array)
 * - nested array at "k" (cross-type conflict pair with both above)
 * - multi-key document with distinct keys (no "k") — can be merged with any above
 */
internal class JsonCrdtConformanceTest : QuiltedConformanceSuite<JsonCrdt>() {

    // One replica per sample — their dot spaces are completely disjoint.
    private val r1 = ReplicaId("R1")
    private val r2 = ReplicaId("R2")
    private val r3 = ReplicaId("R3")
    private val r4 = ReplicaId("R4")
    private val r5 = ReplicaId("R5")

    private fun leaf(r: ReplicaId, v: String) =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(r, JsonValue.Str(v)))

    private fun num(r: ReplicaId, n: Double) =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(r, JsonValue.Num(n)))

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

    override fun samples(): List<JsonCrdt> = listOf(
        // empty — identity element
        JsonCrdt.empty(r1),
        // leaf at "k" (replica R2) — cross-type conflict pair with the object below
        JsonCrdt.empty(r2).set("k", leaf(r2, "scalar")),
        // object at "k" (replica R3) — cross-type conflict pair with the leaf above and array below
        JsonCrdt.empty(r3).set("k", objNode(r3, "x" to num(r3, 1.0))),
        // array at "k" (replica R4) — cross-type conflict pair with both above
        JsonCrdt.empty(r4).set("k", arrNode(r4, leaf(r4, "item"))),
        // multi-key document (replica R5) — distinct keys so it merges additively with the above
        JsonCrdt.empty(r5)
            .set("name", leaf(r5, "Alice"))
            .set("meta", objNode(r5, "active" to leaf(r5, "true"))),
    )
}
