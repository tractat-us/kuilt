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
 *
 * Test setup convention: when two replicas independently edit a document,
 * they must use distinct [ReplicaId]s. This mirrors real usage where each
 * peer has a globally-unique identity. The helper functions below use `a` for
 * replica A and `b` for replica B throughout.
 */
class JsonCrdtTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val json = Json { allowStructuredMapKeys = true }

    // ---- JsonNode factory helpers ----

    private fun leaf(v: JsonValue) = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(a, v))
    private fun str(s: String) = leaf(JsonValue.Str(s))
    private fun num(n: Double) = leaf(JsonValue.Num(n))
    private fun bool(f: Boolean) = leaf(JsonValue.Bool(f))
    private fun nullLeaf() = leaf(JsonValue.Null)

    /**
     * Build a [JsonNode.Object] whose entries are keyed by [replica].
     * Using the caller-supplied replica ensures the resulting ORMap's dot
     * space doesn't collide with other maps built by a different replica.
     */
    private fun obj(replica: ReplicaId, vararg pairs: Pair<String, JsonNode>): JsonNode.Object {
        val map = pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) ->
            acc.put(replica, k, v)
        }
        return JsonNode.Object(map)
    }

    private fun arr(replica: ReplicaId, vararg elements: JsonNode): JsonNode.Array {
        val rga = elements.foldIndexed(Rga.empty<JsonNode>()) { i, acc, elem ->
            val afterId = if (i == 0) RgaId.HEAD else acc.sequence.last()
            acc.insertAfter(replica, afterId, elem).first
        }
        return JsonNode.Array(rga)
    }

    // ---- Tests: basic get/set ----

    @Test
    fun emptyDocHasNoKeys() {
        assertEquals(emptySet<String>(), JsonCrdt.empty(a).keys)
    }

    @Test
    fun setThenGet() {
        val doc = JsonCrdt.empty(a).set("name", str("Alice"))
        assertIs<JsonNode.Leaf>(doc["name"])
        assertEquals(setOf(JsonValue.Str("Alice")), (doc["name"] as JsonNode.Leaf).register.values)
    }

    @Test
    fun getReturnsNullForMissingKey() {
        assertNull(JsonCrdt.empty(a)["missing"])
    }

    @Test
    fun removeKey() {
        val doc = JsonCrdt.empty(a).set("x", str("hi")).remove("x")
        assertNull(doc["x"])
    }

    // ---- Tests: nested objects ----

    /**
     * Replica A adds "name" to "profile"; replica B concurrently adds "age".
     * Both diverge from a shared base. After merge both keys should be present.
     *
     * Each replica uses its own [ReplicaId] when modifying the inner ORMap so
     * their dot spaces don't collide.
     */
    @Test
    fun nestedObjectMerge() {
        // shared base: "profile" key exists, inner map is empty
        val base = JsonCrdt.empty(a).set("profile", JsonNode.Object(ORMap.empty()))

        // replica A adds "name" to profile — uses replica a in the inner map
        val docA = base.set("profile", obj(a, "name" to str("Alice")))

        // replica B concurrently adds "age" to profile — uses replica b in the inner map
        val docB = base.withReplica(b).set("profile", obj(b, "age" to num(30.0)))

        val merged = docA.piece(docB)
        val profile = assertIs<JsonNode.Object>(merged["profile"])
        assertEquals(setOf("name", "age"), profile.map.keys)
    }

    // ---- Tests: add-wins key semantics ----

    @Test
    fun addWinsOverConcurrentRemove() {
        // shared base: "x" key exists
        val base = JsonCrdt.empty(a).set("x", str("hello"))
        // replica A removes x
        val docA = base.remove("x")
        // replica B concurrently re-adds x with a new value (uses b to avoid dot collision)
        val docB = base.withReplica(b).set("x", str("world"))
        val merged = docA.piece(docB)
        // B's concurrent add wins
        assertContains(merged.keys, "x")
    }

    // ---- Tests: concurrent scalar → multi-value ----

    @Test
    fun concurrentScalarWritesProduceMultiValue() {
        val base = JsonCrdt.empty(a)
        val docA = base.set("flag", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(a, JsonValue.Str("x"))))
        val docB = base.withReplica(b).set("flag", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(b, JsonValue.Str("y"))))
        val merged = docA.piece(docB)
        val leaf = assertIs<JsonNode.Leaf>(merged["flag"])
        assertEquals(setOf(JsonValue.Str("x"), JsonValue.Str("y")), leaf.register.values)
    }

    // ---- Tests: array merge ----

    @Test
    fun arrayMergeUnionsOpLogs() {
        // Replica A creates a list with "a", "b"; replica B independently creates a list with "c".
        // When merged, the Rga set-union preserves all 3 elements.
        val base = JsonCrdt.empty(a)
        val docA = base.set("list", arr(a, str("a"), str("b")))
        // Replica B uses its own identity — distinct Lamport/seq, no collision with A's ops.
        val docB = base.withReplica(b).set("list", arr(b, str("c")))
        val merged = docA.piece(docB)
        val list = assertIs<JsonNode.Array>(merged["list"])
        // Both arrays' ops survive after union — total visible count is 3
        assertEquals(3, list.rga.toList().size)
    }

    // ---- Tests: deep nesting ----

    @Test
    fun deeplyNestedObjectMerge() {
        // Replica A: root.inner has "value"
        val innerA = obj(a, "value" to num(1.0))
        val outerA = obj(a, "inner" to innerA)
        val docA = JsonCrdt.empty(a).set("root", outerA)

        // Replica B: root.inner has "extra" (B uses its own replica for all inner maps)
        val innerB = obj(b, "extra" to bool(true))
        val outerB = obj(b, "inner" to innerB)
        val docB = JsonCrdt.empty(b).set("root", outerB)

        val merged = docA.piece(docB)
        val root = assertIs<JsonNode.Object>(merged["root"])
        val innerMerged = assertIs<JsonNode.Object>(root.map["inner"])
        assertEquals(setOf("value", "extra"), innerMerged.map.keys)
    }

    // ---- Tests: serialization round-trips ----

    @Test
    fun leafRoundTripsThroughJson() {
        val node = str("hello")
        val ser = JsonNode.serializer()
        // MVRegister uses DotFun<V> which has Map<Dot,V> — Dot is a data class and
        // requires allowStructuredMapKeys in JSON.
        assertEquals(node, json.decodeFromString(ser, json.encodeToString(ser, node)))
    }

    @Test
    fun objectRoundTripsThroughJson() {
        val node = obj(a, "x" to str("v"), "n" to num(42.0))
        val ser = JsonNode.serializer()
        assertEquals(node, json.decodeFromString(ser, json.encodeToString(ser, node)))
    }

    @Test
    fun arrayRoundTripsThroughJson() {
        val node = arr(a, str("a"), bool(true), nullLeaf())
        val ser = JsonNode.serializer()
        assertEquals(node, json.decodeFromString(ser, json.encodeToString(ser, node)))
    }

    @Test
    fun jsonCrdtRoundTripsThroughJson() {
        val crdt = JsonCrdt.empty(a)
            .set("name", str("Alice"))
            .set("tags", arr(a, str("admin"), str("user")))
        val ser = JsonCrdt.serializer()
        assertEquals(crdt, json.decodeFromString(ser, json.encodeToString(ser, crdt)))
    }

    // ---- Tests: lattice laws ----

    @Test
    fun pieceIsIdempotent() {
        val crdt = JsonCrdt.empty(a).set("x", str("v"))
        assertEquals(crdt, crdt.piece(crdt))
    }

    @Test
    fun pieceIsCommutative() {
        val docA = JsonCrdt.empty(a).set("a", str("alpha"))
        val docB = JsonCrdt.empty(b).set("b", str("beta"))
        assertEquals(docA.piece(docB), docB.piece(docA))
    }

    @Test
    fun pieceIsAssociative() {
        val c = ReplicaId("C")
        val docA = JsonCrdt.empty(a).set("a", str("1"))
        val docB = JsonCrdt.empty(b).set("b", str("2"))
        val docC = JsonCrdt.empty(c).set("c", str("3"))
        assertEquals(docA.piece(docB).piece(docC), docA.piece(docB.piece(docC)))
    }
}
