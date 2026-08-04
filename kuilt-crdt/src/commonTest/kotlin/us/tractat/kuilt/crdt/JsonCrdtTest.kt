package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    // ── associativity (#2086) ─────────────────────────────────────────────────────
    //
    // VERDICT: JsonCrdt.piece is NOT associative. It inherits ORMap's defect, at every
    // depth of the document, and the value it loses or resurrects is a whole subtree.
    //
    // These tests characterise the divergence as it stands today rather than assert the law.
    // When #2086 fixes ORMapEntry.join, each `assertNotEquals` below must flip to the
    // `assertEquals` its message names — that is the point of writing them this way. The
    // trajectory test at the bottom already asserts the *other* half of the law (with removes
    // taken out of the generator, JsonCrdt is associative over ~60k causally-related triples),
    // so the fix has a green target to aim at as well as a red one to clear.
    //
    // Why the divergence exists: ORMapEntry.join calls `value.piece(other.value)` whenever both
    // sides hold the key, while ORMap.remove drops the value and keeps only the retired tags in
    // the context. So whether the pre-remove value is still standing next to the post-remove one
    // at the moment the join runs — which is exactly what the bracketing decides — decides
    // whether it comes back. JsonNode.Object wraps ORMap<String, JsonNode>, and JsonCrdt's own
    // root is one, so the same hazard exists once per object in the document.

    /**
     * A leaf whose register dot is minted by [writer].
     *
     * [leaf] mints every register dot under replica `a`, so two leaves built with it both carry
     * `(A,1)` and their merge is settled by which side of `DotFun.join` they arrive on rather than
     * by causality. Harmless for the round-trip and cross-type tests above, which never merge two
     * independently-built leaves at one key — but fatal here, because what these tests measure is
     * precisely *which concurrent scalar writes survive*.
     */
    private fun scalar(writer: String, value: String): JsonNode.Leaf =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(ReplicaId(writer), JsonValue.Str(value)))

    /** An object node whose keys are minted by [writer], so nested dot spaces stay disjoint. */
    private fun objectNode(writer: String, vararg pairs: Pair<String, JsonNode>): JsonNode.Object =
        JsonNode.Object(
            pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) -> acc.put(ReplicaId(writer), k, v) },
        )

    /** An array node whose insert ops are minted by [writer]. */
    private fun arrayNode(writer: String, vararg elements: JsonNode): JsonNode.Array =
        JsonNode.Array(
            elements.fold(Rga.empty<JsonNode>()) { acc, element ->
                acc.insertAfter(ReplicaId(writer), acc.sequence.lastOrNull() ?: RgaId.HEAD, element).first
            },
        )

    /** The scalars a document holds at [key], or `null` if [key] is absent or not a leaf. */
    private fun JsonCrdt.scalarsAt(key: String): Set<JsonValue>? =
        (this[key] as? JsonNode.Leaf)?.register?.values

    /**
     * **The counterexample.** One replica sets a key, removes it, and sets it again — a single
     * trajectory, so every state is causally reachable from the last and no dot is minted twice.
     *
     * `(x⊔y)⊔z` retires the first write before the third state is ever consulted, so only the
     * second scalar survives. `x⊔(y⊔z)` builds a right-hand state that holds the key again, so the
     * outer join finds the key on *both* sides, merges the values, and the first scalar comes back
     * from the dead. The two results are different documents, reachable from the same history.
     */
    @Test
    fun pieceIsNotAssociativeAcrossARemoveBetweenTwoSets() {
        val first = JsonCrdt.empty(a).set("k", scalar("W1", "v1"))
        val removed = first.remove("k")
        val reSet = removed.set("k", scalar("W2", "v2"))

        val left = first.piece(removed).piece(reSet)
        val right = first.piece(removed.piece(reSet))

        assertAll(
            {
                assertNotEquals(
                    left,
                    right,
                    "#2086: when this passes, ORMap's value merge has been fixed — replace this " +
                        "test with assertEquals(left, right)\n  (x⊔y)⊔z = $left\n  x⊔(y⊔z) = $right",
                )
            },
            {
                assertEquals(
                    setOf<JsonValue>(JsonValue.Str("v2")),
                    left.scalarsAt("k"),
                    "(x⊔y)⊔z: the removed write must stay removed",
                )
            },
            {
                assertEquals(
                    setOf<JsonValue>(JsonValue.Str("v1"), JsonValue.Str("v2")),
                    right.scalarsAt("k"),
                    "x⊔(y⊔z): the removed write is resurrected and merged into the re-set leaf",
                )
            },
            {
                assertEquals(
                    setOf("k"),
                    left.keys,
                    "key presence is add-wins in both groupings — only the *value* diverges",
                )
            },
            { assertEquals(setOf("k"), right.keys, "key presence must agree across groupings") },
        )
    }

    /**
     * The same defect with a **subtree** in the value position: the thing that survives or does not
     * is an entire nested object, not one scalar.
     *
     * This is the amplification `ORMap` alone does not show. `ORMap<String, GSet<String>>` loses a
     * handful of elements; here `x⊔(y⊔z)` brings back every key of a discarded JSON object —
     * arbitrarily deep, arbitrarily large — and grafts it onto the replacement.
     */
    @Test
    fun aRemovedSubtreeIsResurrectedInOneGroupingOnly() {
        val first = JsonCrdt.empty(a).set("obj", objectNode("W1", "p" to scalar("W1", "p1"), "r" to scalar("W1", "r1")))
        val removed = first.remove("obj")
        val reSet = removed.set("obj", objectNode("W2", "q" to scalar("W2", "q1")))

        val left = first.piece(removed).piece(reSet)
        val right = first.piece(removed.piece(reSet))

        assertAll(
            {
                assertNotEquals(
                    left,
                    right,
                    "#2086: when this passes, replace with assertEquals — nested subtree case",
                )
            },
            {
                assertEquals(
                    setOf("q"),
                    (left["obj"] as? JsonNode.Object)?.map?.keys,
                    "(x⊔y)⊔z: the discarded subtree stays discarded",
                )
            },
            {
                assertEquals(
                    setOf("p", "r", "q"),
                    (right["obj"] as? JsonNode.Object)?.map?.keys,
                    "x⊔(y⊔z): the whole discarded subtree is grafted back onto the replacement",
                )
            },
        )
    }

    /**
     * The defect **recurses**: it is not a property of the document root. Here the outer key is
     * never removed — only a key of the nested object is — and the two groupings still disagree,
     * one level down. A document `n` objects deep has `n` independent instances of this hazard.
     */
    @Test
    fun theDivergenceRecursesIntoANestedObject() {
        val inner = ORMap.empty<String, JsonNode>().put(ReplicaId("Q"), "p", scalar("W1", "p1"))
        val innerRemoved = inner.remove("p")
        val innerReSet = innerRemoved.put(ReplicaId("Q"), "p", scalar("W2", "p2"))

        val x = JsonCrdt.empty(a).set("obj", JsonNode.Object(inner))
        val y = JsonCrdt.empty(a).set("obj", JsonNode.Object(innerRemoved))
        val z = JsonCrdt.empty(a).set("obj", JsonNode.Object(innerReSet))

        val leftInner = (x.piece(y).piece(z)["obj"] as? JsonNode.Object)?.map?.get("p")
        val rightInner = (x.piece(y.piece(z))["obj"] as? JsonNode.Object)?.map?.get("p")

        assertAll(
            {
                assertNotEquals(
                    x.piece(y).piece(z),
                    x.piece(y.piece(z)),
                    "#2086: when this passes, replace with assertEquals — nested-ORMap case",
                )
            },
            {
                assertEquals(
                    setOf<JsonValue>(JsonValue.Str("p2")),
                    (leftInner as? JsonNode.Leaf)?.register?.values,
                    "(x⊔y)⊔z, one level down",
                )
            },
            {
                assertEquals(
                    setOf<JsonValue>(JsonValue.Str("p1"), JsonValue.Str("p2")),
                    (rightInner as? JsonNode.Leaf)?.register?.values,
                    "x⊔(y⊔z), one level down: the inner removed write is resurrected",
                )
            },
        )
    }

    /**
     * The value position is a **merging lattice of its own** — an [Rga] — and the resurrection
     * therefore lands *inside* the sequence: the replacement array grows an element that a
     * `remove` had already discarded, in one grouping and not the other.
     */
    @Test
    fun aRemovedArrayElementIsResurrectedInOneGroupingOnly() {
        val first = JsonCrdt.empty(a).set("xs", arrayNode("W1", scalar("W1", "e1")))
        val removed = first.remove("xs")
        val reSet = removed.set("xs", arrayNode("W2", scalar("W2", "e2")))

        val left = (first.piece(removed).piece(reSet)["xs"] as? JsonNode.Array)?.rga
        val right = (first.piece(removed.piece(reSet))["xs"] as? JsonNode.Array)?.rga

        assertAll(
            {
                assertNotEquals(
                    left,
                    right,
                    "#2086: when this passes, replace with assertEquals — Rga-valued case",
                )
            },
            { assertEquals(1, left?.size, "(x⊔y)⊔z: the removed array stays gone") },
            { assertEquals(2, right?.size, "x⊔(y⊔z): the removed array's element joins the replacement") },
        )
    }

    /**
     * A *concurrent* set and remove is **not** enough to diverge, which is what makes the
     * counterexample specific rather than "removes are broken".
     *
     * Here the setter still holds the key when it writes, so [ORMap.put] merges the old value into
     * the new one locally and carries it along — every grouping sees the same value. The divergence
     * needs the re-set to happen on a state where the key is *absent*, so the merge is skipped at
     * write time and re-appears at join time only under one bracketing.
     */
    @Test
    fun pieceIsAssociativeAcrossAConcurrentSetAndRemove() {
        val start = JsonCrdt.empty(a).set("k", scalar("W1", "v1"))
        val remover = start.remove("k")
        val setter = start.withReplica(b).set("k", scalar("W2", "v2"))

        assertAll(
            *associativityChecks("concurrent set/remove", remover, setter, start),
            {
                assertEquals(
                    setOf<JsonValue>(JsonValue.Str("v1"), JsonValue.Str("v2")),
                    remover.piece(setter).piece(start).scalarsAt("k"),
                    "add-wins: the concurrent write survives the remove, carrying the value it saw",
                )
            },
        )
    }

    /**
     * Cross-type precedence (`Object > Array > Leaf`) over a **causal chain**, which
     * [crossTypePieceIsAssociative] above does not reach: it folds its three documents from three
     * independent `empty()`s, so no state there is an ancestor of another. The type dimension is a
     * total order and `max` over one is associative — this test is what says so on states that
     * could actually expose an ordering bug.
     */
    @Test
    fun pieceIsAssociativeOnCrossTypeConflictsOverACausalChain() {
        val asLeaf = JsonCrdt.empty(a).set("k", scalar("W1", "v1"))
        val asArray = asLeaf.set("k", arrayNode("W2", scalar("W2", "e1")))
        val asObject = asArray.set("k", objectNode("W3", "p" to scalar("W3", "p1")))

        assertAll(
            *associativityChecks("cross-type chain", asLeaf, asArray, asObject),
            {
                assertIs<JsonNode.Object>(
                    asLeaf.piece(asArray).piece(asObject)["k"],
                    "the richest type must win regardless of grouping",
                )
            },
        )
    }

    /**
     * **The general law over causally-related trajectories, run twice.** With `remove` taken out of
     * the generator, `JsonCrdt.piece` is associative across all 66,642 ordered triples drawn from
     * shared histories — objects, arrays, scalars, cross-type overwrites, merges and all. Put
     * `remove` back and 648 of the same 66,642 triples diverge, about one in a hundred.
     *
     * Two assertions, and each can fail on its own:
     * - the *control* arm pins the null result — no part of `JsonNode`'s own algebra (the type
     *   precedence rule, `Rga.piece`, `MVRegister.piece`) is non-associative;
     * - the *positive* arm pins the defect, and keeps the control honest — a generator that
     *   degenerated into producing nothing would make both arms zero, and the second arm would then
     *   red-light rather than pass vacuously.
     *
     * This is the coverage the existing surfaces cannot give. [pieceIsAssociative] and
     * [crossTypePieceIsAssociative] fold each of their three documents from a separate
     * `JsonCrdt.empty()`, so no state is ever a causal ancestor of another and no dot is ever
     * superseded across the triple — and neither uses `remove` at all. `ORMapLawsPropertyTest`
     * (jvmTest, jqwik) is built the same way and passes on the `ORMap` that *is* non-associative.
     */
    @Test
    fun removeIsTheOnlySourceOfNonAssociativity() {
        val control = searchTrajectories(allowRemove = false)
        val withRemoves = searchTrajectories(allowRemove = true)

        assertAll(
            {
                assertEquals(
                    0,
                    control.violations,
                    "JsonCrdt's own algebra must be associative once removes are out of the " +
                        "generator (${control.triples} triples).\n${control.firstViolation}",
                )
            },
            {
                assertTrue(
                    withRemoves.violations > 0,
                    "#2086: no violation found in ${withRemoves.triples} triples *with* removes. " +
                        "Either ORMap has been fixed — in which case assert 0 here and flip the " +
                        "assertNotEquals tests above — or the generator stopped producing the " +
                        "re-set-after-remove shape (${withRemoves.reSetsAfterRemove} produced).",
                )
            },
            {
                assertTrue(
                    control.reSets >= MIN_RE_SETS,
                    "vacuous control: only ${control.reSets} overwrites of a live key, so the " +
                        "value-merge path was barely exercised without removes",
                )
            },
            {
                assertTrue(
                    control.objectValues >= MIN_STRUCTURED && control.arrayValues >= MIN_STRUCTURED,
                    "vacuous control: ${control.objectValues} object and ${control.arrayValues} " +
                        "array values written — the recursive merge paths must both be exercised",
                )
            },
            {
                assertTrue(
                    withRemoves.reSetsAfterRemove >= MIN_RE_SETS_AFTER_REMOVE,
                    "vacuous: only ${withRemoves.reSetsAfterRemove} writes landed on a key some " +
                        "replica had already removed, so the shape that breaks ORMap was barely built",
                )
            },
        )
    }

    /** What one run of the trajectory search found. */
    private class SearchResult(
        val triples: Int,
        val violations: Int,
        val reSets: Int,
        val reSetsAfterRemove: Int,
        val objectValues: Int,
        val arrayValues: Int,
        val firstViolation: String?,
    )

    /**
     * Builds [TRAJECTORY_TRIALS] shared histories — three replicas writing scalars, objects and
     * arrays over a small key pool, merging with each other, optionally removing — and checks every
     * ordered triple of the snapshots taken along the way in both groupings.
     *
     * Every state comes out of one history, so dots are unique by construction and states stand in
     * genuine ancestor/descendant relationships. That is the whole point: the defect only shows on
     * a triple where one state has retired a dot another still carries.
     */
    private fun searchTrajectories(allowRemove: Boolean): SearchResult {
        val random = Random(2086)
        var triples = 0
        var violations = 0
        var reSets = 0
        var reSetsAfterRemove = 0
        var objectValues = 0
        var arrayValues = 0
        var firstViolation: String? = null
        var uid = 0

        repeat(TRAJECTORY_TRIALS) { trial ->
            val live = TRAJECTORY_REPLICAS.associateWith { JsonCrdt.empty(it) }.toMutableMap()
            val snapshots = mutableListOf<JsonCrdt>()
            val removedAnywhere = mutableSetOf<String>()

            repeat(random.nextInt(8, 13)) {
                val author = TRAJECTORY_REPLICAS.random(random)
                val key = TRAJECTORY_KEYS.random(random)
                val state = live.getValue(author)
                val writer = "W${uid++}"
                if (key in state.keys) reSets++
                if (key in removedAnywhere) reSetsAfterRemove++

                live[author] = when (random.nextInt(6)) {
                    0, 1 -> state.set(key, scalar(writer, "v${random.nextInt(4)}"))
                    2 -> {
                        objectValues++
                        state.set(key, objectNode(writer, "p${random.nextInt(3)}" to scalar(writer, "n")))
                    }
                    3 -> {
                        arrayValues++
                        state.set(key, arrayNode(writer, scalar(writer, "e${random.nextInt(3)}")))
                    }
                    4 -> if (allowRemove) {
                        if (key in state.keys) removedAnywhere += key
                        state.remove(key)
                    } else {
                        state.set(key, scalar(writer, "w"))
                    }
                    else -> state.piece(live.getValue(TRAJECTORY_REPLICAS.random(random)))
                }
                snapshots += live.getValue(author)
            }

            for (x in snapshots) {
                for (y in snapshots) {
                    for (z in snapshots) {
                        triples++
                        val left = x.piece(y).piece(z)
                        val right = x.piece(y.piece(z))
                        if (left != right) {
                            violations++
                            if (firstViolation == null) {
                                firstViolation = "trial $trial:\n  x = $x\n  y = $y\n  z = $z\n" +
                                    "  (x⊔y)⊔z = $left\n  x⊔(y⊔z) = $right"
                            }
                        }
                    }
                }
            }
        }

        return SearchResult(
            triples, violations, reSets, reSetsAfterRemove, objectValues, arrayValues, firstViolation,
        )
    }

    /** Associativity for each of the six orderings of one triple: both groupings must agree. */
    private fun associativityChecks(
        label: String,
        first: JsonCrdt,
        second: JsonCrdt,
        third: JsonCrdt,
    ): Array<() -> Unit> {
        val orderings = listOf(
            Triple(first, second, third),
            Triple(first, third, second),
            Triple(second, first, third),
            Triple(second, third, first),
            Triple(third, first, second),
            Triple(third, second, first),
        )
        return orderings.mapIndexed { index, (x, y, z) ->
            {
                val left = x.piece(y).piece(z)
                val right = x.piece(y.piece(z))
                assertEquals(
                    left,
                    right,
                    "$label: ordering $index — (x⊔y)⊔z and x⊔(y⊔z) disagree.\n" +
                        "  x       = $x\n  y       = $y\n  z       = $z\n" +
                        "  (x⊔y)⊔z = $left\n  x⊔(y⊔z) = $right",
                )
            }
        }.toTypedArray()
    }

    private companion object {
        /** Shared histories per run of the trajectory law. */
        const val TRAJECTORY_TRIALS = 60

        /**
         * Floors the generator must clear. **Measured on seed 2086, over 66,642 triples per arm:
         * the control made 289 overwrites of a live key, 105 object values and 105 array values;
         * the removes arm re-set a key some replica had already removed 37 times and found 648
         * violations.** Each floor sits near half its measurement, so an incidental tweak does not
         * red-light the suite while a generator that stopped writing structured values — or stopped
         * re-setting after a remove, the shape that breaks `ORMap` — fails loudly instead of
         * passing vacuously.
         */
        const val MIN_RE_SETS = 140
        const val MIN_STRUCTURED = 50
        const val MIN_RE_SETS_AFTER_REMOVE = 15

        val TRAJECTORY_REPLICAS = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))

        /** A small pool, so branches collide on the same key and states become genuine ancestors. */
        val TRAJECTORY_KEYS = listOf("k1", "k2")
    }
}
