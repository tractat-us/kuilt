package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [JsonCrdt] — a recursive CRDT over arbitrary JSON that composes
 * [ORMap], [Rga], and [MVRegister].
 *
 * Test setup convention: when two replicas independently edit a document,
 * they must use distinct [ReplicaId]s. This mirrors real usage where each
 * peer has a globally-unique identity. The helper functions below use `a` for
 * replica A and `b` for replica B throughout.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonCrdtTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val json = Json { allowStructuredMapKeys = true }
    private val cbor = Cbor

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
        val base = JsonCrdt.empty(a).set("profile", JsonNode.Object(ORMap.empty()))
        val docA = base.set("profile", obj(a, "name" to str("Alice")))
        val docB = base.withReplica(b).set("profile", obj(b, "age" to num(30.0)))
        val merged = docA.piece(docB)
        val profile = assertIs<JsonNode.Object>(merged["profile"])
        assertEquals(setOf("name", "age"), profile.map.keys)
    }

    // ---- Tests: add-wins key semantics ----

    @Test
    fun addWinsOverConcurrentRemove() {
        val base = JsonCrdt.empty(a).set("x", str("hello"))
        val docA = base.remove("x")
        val docB = base.withReplica(b).set("x", str("world"))
        val merged = docA.piece(docB)
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
        val base = JsonCrdt.empty(a)
        val docA = base.set("list", arr(a, str("a"), str("b")))
        val docB = base.withReplica(b).set("list", arr(b, str("c")))
        val merged = docA.piece(docB)
        val list = assertIs<JsonNode.Array>(merged["list"])
        assertEquals(3, list.rga.toList().size)
    }

    // ---- Tests: deep nesting ----

    @Test
    fun deeplyNestedObjectMerge() {
        val innerA = obj(a, "value" to num(1.0))
        val outerA = obj(a, "inner" to innerA)
        val docA = JsonCrdt.empty(a).set("root", outerA)

        val innerB = obj(b, "extra" to bool(true))
        val outerB = obj(b, "inner" to innerB)
        val docB = JsonCrdt.empty(b).set("root", outerB)

        val merged = docA.piece(docB)
        val root = assertIs<JsonNode.Object>(merged["root"])
        val innerMerged = assertIs<JsonNode.Object>(root.map["inner"])
        assertEquals(setOf("value", "extra"), innerMerged.map.keys)
    }

    // ---- Tests: cross-type conflict resolution ----

    /**
     * Object wins over Leaf in both merge orders — the tiebreak is commutative
     * and the losing Leaf's data is irrecoverably dropped (documented behaviour).
     */
    @Test
    fun objectWinsOverLeafBothMergeOrders() {
        val docObject = JsonCrdt.empty(a).set("k", obj(a, "x" to str("v")))
        val docLeaf = JsonCrdt.empty(b).set("k", str("scalar"))
        val merged1 = docObject.piece(docLeaf)
        val merged2 = docLeaf.piece(docObject)
        assertIs<JsonNode.Object>(merged1["k"])
        assertIs<JsonNode.Object>(merged2["k"])
        assertEquals(merged1, merged2)  // commutative
    }

    @Test
    fun objectWinsOverArrayBothMergeOrders() {
        val docObject = JsonCrdt.empty(a).set("k", obj(a, "x" to str("v")))
        val docArray = JsonCrdt.empty(b).set("k", arr(b, str("item")))
        val merged1 = docObject.piece(docArray)
        val merged2 = docArray.piece(docObject)
        assertIs<JsonNode.Object>(merged1["k"])
        assertIs<JsonNode.Object>(merged2["k"])
        assertEquals(merged1, merged2)
    }

    @Test
    fun arrayWinsOverLeafBothMergeOrders() {
        val docArray = JsonCrdt.empty(a).set("k", arr(a, str("item")))
        val docLeaf = JsonCrdt.empty(b).set("k", str("scalar"))
        val merged1 = docArray.piece(docLeaf)
        val merged2 = docLeaf.piece(docArray)
        assertIs<JsonNode.Array>(merged1["k"])
        assertIs<JsonNode.Array>(merged2["k"])
        assertEquals(merged1, merged2)
    }

    /**
     * Setting a key to Object then Leaf on the same replica produces Object
     * (because [ORMap.put] pieces the existing value with the new one).
     * This is a local consequence of the additive put: the Object dominates.
     */
    @Test
    fun localRetypeObjectThenLeafKeepsObject() {
        val doc = JsonCrdt.empty(a)
            .set("k", obj(a, "x" to str("v")))
            .set("k", str("scalar"))
        assertIs<JsonNode.Object>(doc["k"])
    }

    /**
     * Three-way associativity with a genuine cross-type conflict at one key.
     * a.piece(b).piece(c) == a.piece(b.piece(c)) even when types differ.
     */
    @Test
    fun crossTypePieceIsAssociative() {
        val c = ReplicaId("C")
        val docA = JsonCrdt.empty(a).set("k", obj(a, "x" to str("v")))
        val docB = JsonCrdt.empty(b).set("k", str("scalar"))
        val docC = JsonCrdt.empty(c).set("k", arr(c, str("item")))
        assertEquals(docA.piece(docB).piece(docC), docA.piece(docB.piece(docC)))
    }

    // ---- Tests: serialization round-trips — JSON ----

    @Test
    fun leafRoundTripsThroughJson() {
        val node = str("hello")
        val ser = JsonNode.serializer()
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

    // ---- Tests: serialization round-trips — CBOR (the Quilter wire format) ----

    /**
     * CBOR is the format used by [Quilter] on the wire. The custom
     * [JsonNodeSerializer] was specifically written to handle the recursive
     * [ORMap] and [Rga] element types that fail with the compiler-generated
     * serializer under CBOR. These tests verify that the hand-rolled descriptor
     * + encode/decode round-trip correctly under CBOR's stricter encoding.
     */
    @Test
    fun leafRoundTripsThroughCbor() {
        val node = str("hello")
        val ser = JsonNode.serializer()
        assertEquals(node, cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, node)))
    }

    @Test
    fun objectRoundTripsThroughCbor() {
        val node = obj(a, "x" to str("v"), "n" to num(42.0))
        val ser = JsonNode.serializer()
        assertEquals(node, cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, node)))
    }

    @Test
    fun arrayRoundTripsThroughCbor() {
        val node = arr(a, str("a"), bool(true), nullLeaf())
        val ser = JsonNode.serializer()
        assertEquals(node, cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, node)))
    }

    @Test
    fun jsonCrdtRoundTripsThroughCbor() {
        val crdt = JsonCrdt.empty(a)
            .set("name", str("Alice"))
            .set("tags", arr(a, str("admin"), str("user")))
            .set("meta", obj(a, "active" to bool(true), "score" to num(9.5)))
        val ser = JsonCrdt.serializer()
        assertEquals(crdt, cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, crdt)))
    }

    @Test
    fun deeplyNestedCrdtRoundTripsThroughCbor() {
        val inner = obj(a, "value" to num(1.0), "label" to str("x"))
        val crdt = JsonCrdt.empty(a)
            .set("profile", obj(a, "name" to str("Alice"), "inner" to inner))
            .set("items", arr(a, str("a"), str("b")))
        val ser = JsonCrdt.serializer()
        assertEquals(crdt, cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, crdt)))
    }

    // ---- Tests: lattice laws ----

    @Test
    fun pieceIsIdempotent() {
        // Use a doc with a shared key to exercise nested-merge idempotence
        val crdt = JsonCrdt.empty(a)
            .set("x", str("v"))
            .set("obj", obj(a, "k" to num(1.0)))
        assertEquals(crdt, crdt.piece(crdt))
    }

    @Test
    fun pieceIsCommutative() {
        // Both replicas write to the same key with different types to exercise conflict
        val docA = JsonCrdt.empty(a).set("shared", obj(a, "ka" to str("va"))).set("a-only", str("a"))
        val docB = JsonCrdt.empty(b).set("shared", arr(b, str("item"))).set("b-only", str("b"))
        assertEquals(docA.piece(docB), docB.piece(docA))
    }

    @Test
    fun pieceIsAssociative() {
        val c = ReplicaId("C")
        val docA = JsonCrdt.empty(a).set("a", str("1")).set("shared", obj(a, "ka" to str("va")))
        val docB = JsonCrdt.empty(b).set("b", str("2")).set("shared", str("scalar"))
        val docC = JsonCrdt.empty(c).set("c", str("3")).set("shared", arr(c, str("item")))
        assertEquals(docA.piece(docB).piece(docC), docA.piece(docB.piece(docC)))
    }

    // ---- Tests: replica guard (F4) ----

    /**
     * A document deserialized without calling [withReplica] must fail loud on mutation
     * rather than silently corrupting the dot namespace with [ReplicaId]("").
     */
    @Test
    fun setFailsOnEmptyReplicaId() {
        val doc = JsonCrdt.empty(a).set("x", str("hello"))
        val deserialized = json.decodeFromString(JsonCrdt.serializer(), json.encodeToString(JsonCrdt.serializer(), doc))
        assertFailsWith<IllegalArgumentException> { deserialized.set("y", str("world")) }
    }

    @Test
    fun removeFailsOnEmptyReplicaId() {
        val doc = JsonCrdt.empty(a).set("x", str("hello"))
        val deserialized = json.decodeFromString(JsonCrdt.serializer(), json.encodeToString(JsonCrdt.serializer(), doc))
        assertFailsWith<IllegalArgumentException> { deserialized.remove("x") }
    }

    @Test
    fun withReplicaAllowsMutationAfterDeserialization() {
        val doc = JsonCrdt.empty(a).set("x", str("hello"))
        val deserialized = json.decodeFromString(JsonCrdt.serializer(), json.encodeToString(JsonCrdt.serializer(), doc))
            .withReplica(a)
        assertEquals(doc, deserialized)
        val updated = deserialized.set("y", str("world"))
        assertContains(updated.keys, "y")
    }

    // ---- Tests: cross-type data-loss semantics (F5) ----

    /**
     * When Object wins a cross-type conflict, the losing Leaf's payload is absent —
     * the winning Object has no record of the scalar value that was discarded.
     */
    @Test
    fun crossTypeMergeObjectOverLeafPayloadIsAbsent() {
        val docObject = JsonCrdt.empty(a).set("k", obj(a, "nested" to str("kept")))
        val docLeaf = JsonCrdt.empty(b).set("k", str("discarded"))
        val merged = docObject.piece(docLeaf)
        val winner = assertIs<JsonNode.Object>(merged["k"])
        // The Object's inner content is intact
        assertEquals(setOf("nested"), winner.map.keys)
        // No trace of the scalar "discarded" — it is silently gone
    }

    /**
     * Two-level cross-type conflict: an Object wins over an Array at the root level;
     * the Array's entire subtree is discarded.
     */
    @Test
    fun crossTypeMergeObjectOverArraySubtreeIsAbsent() {
        val docObject = JsonCrdt.empty(a).set("k", obj(a, "x" to str("v")))
        val docArray = JsonCrdt.empty(b).set("k", arr(b, str("item1"), str("item2")))
        val merged1 = docObject.piece(docArray)
        val merged2 = docArray.piece(docObject)
        // Object wins in both merge orders
        assertIs<JsonNode.Object>(merged1["k"])
        assertIs<JsonNode.Object>(merged2["k"])
        // The Array's elements are gone — nothing about "item1"/"item2" survives
        assertEquals(merged1, merged2)
    }

    // ---- Tests: post-deserialize convergence (F6) ----

    /**
     * Deserialize two independently-evolved documents from CBOR, piece them, and
     * assert the result matches an in-memory merge of the originals. Proves the
     * wire format preserves enough causal context for correct convergence.
     */
    @Test
    fun deserializedDocumentsConvergeCorrectly() {
        val ser = JsonCrdt.serializer()
        val docA = JsonCrdt.empty(a)
            .set("shared", obj(a, "name" to str("Alice")))
            .set("a-only", str("from-a"))
        val docB = JsonCrdt.empty(b)
            .set("shared", obj(b, "age" to num(30.0)))
            .set("b-only", str("from-b"))
        val expected = docA.piece(docB)

        val deserA = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, docA))
        val deserB = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, docB))
        val actual = deserA.piece(deserB)

        assertEquals(expected, actual)
    }

    // ---- Tests: causalDots recursion (F1) ----

    /**
     * A [JsonCrdt] containing a nested [JsonNode.Array] must expose the embedded
     * [Rga]'s causal dots via [causalDots], so [Quilter] can build the
     * correct causal-stability frontier. An empty set here would suppress GC for
     * all nested Rga tombstones.
     */
    @Test
    fun causalDotsIncludesNestedArrayDots() {
        val doc = JsonCrdt.empty(a).set("items", arr(a, str("x"), str("y")))
        val dots = doc.causalDots()
        // arr() builds an Rga with Insert ops minted by replica a — those dots
        // must surface at the JsonCrdt level.
        assertTrue(dots.isNotEmpty(), "causalDots() must not be empty for a document with nested Rga arrays")
        assertTrue(dots.all { it.replica == a }, "All dots should belong to replica $a")
    }

    @Test
    fun causalDotsIncludesDeeplyNestedArrayDots() {
        val innerArr = arr(a, str("deep"))
        val outerObj = obj(a, "list" to innerArr)
        val doc = JsonCrdt.empty(a).set("root", outerObj)
        val dots = doc.causalDots()
        assertTrue(dots.isNotEmpty(), "causalDots() must recurse into nested Object→Array")
    }
}
