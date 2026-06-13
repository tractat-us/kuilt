package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for [JsonCrdt] — a recursive CRDT over arbitrary JSON that composes
 * [ORMap], [Rga], and [MVRegister].
 */
class JsonCrdtTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    // ---- construction helpers ----

    private fun empty() = JsonCrdt.empty(a)

    private fun doc(vararg pairs: Pair<String, JsonNode>) =
        pairs.fold(JsonCrdt.empty(a)) { acc, (k, v) -> acc.set(k, v) }

    // ---- JsonNode factory helpers ----

    private fun leaf(v: JsonValue) = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(a, v))
    private fun str(s: String) = leaf(JsonValue.Str(s))
    private fun num(n: Double) = leaf(JsonValue.Num(n))
    private fun bool(b: Boolean) = leaf(JsonValue.Bool(b))
    private fun nullLeaf() = leaf(JsonValue.Null)
    private fun obj(vararg pairs: Pair<String, JsonNode>): JsonNode.Object {
        val map = pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) ->
            acc.put(a, k, v)
        }
        return JsonNode.Object(map)
    }

    private fun arr(vararg elements: JsonNode): JsonNode.Array {
        val rga = elements.foldIndexed(Rga.empty<JsonNode>()) { i, acc, elem ->
            val afterId = if (i == 0) RgaId.HEAD else acc.sequence.last()
            acc.insertAfter(a, afterId, elem).first
        }
        return JsonNode.Array(rga)
    }

    // ---- Tests: basic get/set ----

    @Test
    fun emptyDocHasNoKeys() {
        assertEquals(emptySet<String>(), empty().keys)
    }

    @Test
    fun setThenGet() {
        val doc = doc("name" to str("Alice"))
        assertIs<JsonNode.Leaf>(doc["name"])
        assertEquals(setOf(JsonValue.Str("Alice")), (doc["name"] as JsonNode.Leaf).register.values)
    }

    @Test
    fun getReturnsNullForMissingKey() {
        assertNull(empty()["missing"])
    }

    @Test
    fun removeKey() {
        val doc = doc("x" to str("hi")).remove("x")
        assertNull(doc["x"])
    }

    // ---- Tests: nested objects ----

    @Test
    fun nestedObjectMerge() {
        val docA = doc("profile" to obj("name" to str("Alice")))
        val docB = doc("profile" to obj("age" to num(30.0)))
        val merged = docA.piece(docB)
        val profile = assertIs<JsonNode.Object>(merged["profile"])
        assertEquals(setOf("name", "age"), profile.map.keys)
    }

    // ---- Tests: add-wins key semantics ----

    @Test
    fun addWinsOverConcurrentRemove() {
        val base = doc("x" to str("hello"))
        val docA = base.remove("x")
        val docB = base.set("x", str("world"))
        val merged = docA.piece(docB)
        // bob's concurrent add wins
        assertContains(merged.keys, "x")
    }

    // ---- Tests: concurrent scalar → multi-value ----

    @Test
    fun concurrentScalarWritesProduceMultiValue() {
        val base = JsonCrdt.empty(a)
        val docA = base.set("flag", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(a, JsonValue.Str("x"))))
        val docB = base.set("flag", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(b, JsonValue.Str("y"))))
        val merged = docA.piece(docB)
        val leaf = assertIs<JsonNode.Leaf>(merged["flag"])
        assertEquals(setOf(JsonValue.Str("x"), JsonValue.Str("y")), leaf.register.values)
    }

    // ---- Tests: array merge ----

    @Test
    fun arrayMergePreservesOrder() {
        val docA = doc("list" to arr(str("a"), str("b")))
        val docB = doc("list" to arr(str("c")))
        // merging two independent arrays: RGA union of both op-logs
        val merged = docA.piece(docB)
        val list = assertIs<JsonNode.Array>(merged["list"])
        // both arrays' ops survive after union — total visible count is 3
        assertEquals(3, list.rga.toList().size)
    }

    // ---- Tests: deep nesting ----

    @Test
    fun deeplyNestedObjectMerge() {
        val inner = obj("value" to num(1.0))
        val outer = obj("inner" to inner)
        val docA = doc("root" to outer)
        val innerB = obj("extra" to bool(true))
        val outerB = obj("inner" to innerB)
        val docB = JsonCrdt.empty(b).set("root", outerB)
        val merged = docA.piece(docB)
        val root = assertIs<JsonNode.Object>(merged["root"])
        val innerMerged = assertIs<JsonNode.Object>(root.map["inner"])
        assertEquals(setOf("value", "extra"), innerMerged.map.keys)
    }

    // ---- Tests: serialization round-trip ----

    @Test
    fun leafRoundTripsThroughJson() {
        val node = str("hello")
        val ser = JsonNode.serializer()
        val json = Json
        assertEquals(node, json.decodeFromString(ser, json.encodeToString(ser, node)))
    }

    @Test
    fun objectRoundTripsThroughJson() {
        val node = obj("x" to str("v"), "n" to num(42.0))
        val ser = JsonNode.serializer()
        val json = Json { allowStructuredMapKeys = true }
        assertEquals(node, json.decodeFromString(ser, json.encodeToString(ser, node)))
    }

    @Test
    fun arrayRoundTripsThroughJson() {
        val node = arr(str("a"), bool(true), nullLeaf())
        val ser = JsonNode.serializer()
        val json = Json { allowStructuredMapKeys = true }
        assertEquals(node, json.decodeFromString(ser, json.encodeToString(ser, node)))
    }

    @Test
    fun jsonCrdtRoundTripsThroughJson() {
        val crdt = doc(
            "name" to str("Alice"),
            "tags" to arr(str("admin"), str("user")),
        )
        val ser = JsonCrdt.serializer()
        val json = Json { allowStructuredMapKeys = true }
        assertEquals(crdt, json.decodeFromString(ser, json.encodeToString(ser, crdt)))
    }

    // ---- Tests: lattice laws ----

    @Test
    fun pieceIsIdempotent() {
        val crdt = doc("x" to str("v"))
        assertEquals(crdt, crdt.piece(crdt))
    }

    @Test
    fun pieceIsCommutative() {
        val docA = doc("a" to str("alpha"))
        val docB = JsonCrdt.empty(b).set("b", str("beta"))
        assertEquals(docA.piece(docB), docB.piece(docA))
    }

    @Test
    fun pieceIsAssociative() {
        val docA = doc("a" to str("1"))
        val docB = JsonCrdt.empty(b).set("b", str("2"))
        val docC = JsonCrdt.empty(ReplicaId("C")).set("c", str("3"))
        assertEquals(docA.piece(docB).piece(docC), docA.piece(docB.piece(docC)))
    }
}
