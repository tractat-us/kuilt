package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

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
    private val r6 = ReplicaId("R6")
    private val r7 = ReplicaId("R7")

    private fun leaf(r: ReplicaId, v: String) =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(r, JsonValue.Str(v)))

    private fun num(r: ReplicaId, n: Double) =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(r, JsonValue.Num(n)))

    private fun objNode(r: ReplicaId, vararg pairs: Pair<String, JsonNode>): JsonNode.Object {
        val map = pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) -> acc.piece { it.put(r, k, v) } }
        return JsonNode.Object(map)
    }

    private fun arrNode(r: ReplicaId, vararg elements: JsonNode): JsonNode.Array {
        val rga = elements.foldIndexed(Rga.empty<JsonNode>()) { i, acc, elem ->
            val afterId = if (i == 0) RgaId.HEAD else acc.sequence.last()
            acc.insertAfter(r, afterId, elem).first
        }
        return JsonNode.Array(rga)
    }

    // R6 writes a key, removes it, then writes it again — the one shape none of the samples above
    // reach, because none of them removes anything. The three are a causal chain on one replica, so
    // they do not alias: each observes the last. The re-asserted leaf is authored by R7 so the
    // register's join keeps both values apart — a leaf re-written under R6's own dot would carry
    // the same tag twice, and a dropped contribution would then be invisible.
    private val jAsserted = JsonCrdt.empty(r6).piece { it.set("j", leaf(r6, "one")) }
    private val jRetired = jAsserted.piece { it.remove("j") }
    private val jReAsserted = jRetired.piece { it.set("j", leaf(r7, "two")) }

    override fun samples(): List<JsonCrdt> = listOf(
        // empty — identity element
        JsonCrdt.empty(r1),
        // leaf at "k" (replica R2) — cross-type conflict pair with the object below
        JsonCrdt.empty(r2).piece { it.set("k", leaf(r2, "scalar")) },
        // object at "k" (replica R3) — cross-type conflict pair with the leaf above and array below
        JsonCrdt.empty(r3).piece { it.set("k", objNode(r3, "x" to num(r3, 1.0))) },
        // array at "k" (replica R4) — cross-type conflict pair with both above
        JsonCrdt.empty(r4).piece { it.set("k", arrNode(r4, leaf(r4, "item"))) },
        // multi-key document (replica R5) — distinct keys so it merges additively with the above
        JsonCrdt.empty(r5)
            .piece { it.set("name", leaf(r5, "Alice")) }
            .piece { it.set("meta", objNode(r5, "active" to leaf(r5, "true"))) },
        jAsserted,
        jRetired,
        jReAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<JsonCrdt> =
        RetirementReAssertion(
            subject = """key "j"""",
            asserted = jAsserted,
            retired = jRetired,
            reAsserted = jReAsserted,
            shows = { "j" in it.keys },
        )
}
