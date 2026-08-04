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
    // VERDICT: JsonCrdt.piece is associative. It was not — it inherited ORMap's defect at every
    // depth of the document, and what it lost or resurrected was a whole subtree.
    //
    // These tests were written against the broken version, each `assertEquals(left, right)` below
    // standing where an `assertNotEquals` characterising the divergence used to. They are kept in
    // that shape on purpose: each one names a *reachable document* whose two groupings must agree,
    // so a future change that reintroduces the hazard fails at the depth it reintroduces it —
    // scalar leaf, nested object, nested Rga — rather than only in the aggregate count.
    //
    // Why the divergence existed: an ORMapEntry was a tag set beside ONE value, so a join keeping
    // both operands' writes had to blend them into that slot, and retiring one of the two tags
    // afterwards kept the blend. Each tag now carries the write made under it, so a write survives
    // exactly as long as its tag. JsonNode.Object wraps ORMap<String, JsonNode> and JsonCrdt's own
    // root is one, so the fix reaches every object in the document — JsonNode's own algebra needed
    // no change, which pieceIsAssociativeOverCausallyRelatedTrajectories' control arm pins.
    //
    // NOTE the semantic this settles, visible in pieceIsAssociativeAcrossAConcurrentSetAndRemove:
    // a remove takes with it the writes sitting on the tags it retired. The key still survives a
    // concurrent write (add-wins), holding that write alone. It is NOT "every write the remover
    // ever saw" — a re-put by a write's own author moves it onto a fresh tag, out of a concurrent
    // remover's reach; ORMapTest.aReplicasRePutCarriesItsEarlierWriteBeyondAConcurrentRemove pins
    // that boundary.

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
     * `(x⊔y)⊔z` retires the first write before the third state is ever consulted. `x⊔(y⊔z)` builds
     * a right-hand state that holds the key again, so the outer join finds the key on *both* sides
     * — and used to merge the values, bringing the first scalar back from the dead. It no longer
     * can: the first write hangs off the tag `y` retired, and goes with it under either grouping.
     */
    @Test
    fun pieceIsAssociativeAcrossARemoveBetweenTwoSets() {
        val first = JsonCrdt.empty(a).set("k", scalar("W1", "v1"))
        val removed = first.remove("k")
        val reSet = removed.set("k", scalar("W2", "v2"))

        val left = first.piece(removed).piece(reSet)
        val right = first.piece(removed.piece(reSet))

        assertAll(
            {
                assertEquals(
                    left,
                    right,
                    "the two groupings must agree\n  (x⊔y)⊔z = $left\n  x⊔(y⊔z) = $right",
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
                    setOf<JsonValue>(JsonValue.Str("v2")),
                    right.scalarsAt("k"),
                    "x⊔(y⊔z): …and must stay removed here too, which is the whole of #2086",
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
     * The same shape with a **subtree** in the value position: what survives or does not is an
     * entire nested object, not one scalar.
     *
     * This is the amplification `ORMap` alone does not show, and it is why the blast radius was
     * worth pinning separately. `ORMap<String, GSet<String>>` lost a handful of elements; here
     * `x⊔(y⊔z)` used to bring back every key of a discarded JSON object — arbitrarily deep,
     * arbitrarily large — and graft it onto the replacement.
     */
    @Test
    fun aRemovedSubtreeStaysDiscardedInEveryGrouping() {
        val first = JsonCrdt.empty(a).set("obj", objectNode("W1", "p" to scalar("W1", "p1"), "r" to scalar("W1", "r1")))
        val removed = first.remove("obj")
        val reSet = removed.set("obj", objectNode("W2", "q" to scalar("W2", "q1")))

        val left = first.piece(removed).piece(reSet)
        val right = first.piece(removed.piece(reSet))

        assertAll(
            { assertEquals(left, right, "the two groupings must agree — nested subtree case") },
            {
                assertEquals(
                    setOf("q"),
                    (left["obj"] as? JsonNode.Object)?.map?.keys,
                    "(x⊔y)⊔z: the discarded subtree stays discarded",
                )
            },
            {
                assertEquals(
                    setOf("q"),
                    (right["obj"] as? JsonNode.Object)?.map?.keys,
                    "x⊔(y⊔z): …and is not grafted back onto the replacement here either",
                )
            },
        )
    }

    /**
     * The hazard **recursed**: it was never a property of the document root. Here the outer key is
     * never removed — only a key of the nested object is — and the two groupings used to disagree
     * one level down, so a document `n` objects deep had `n` independent instances. The fix reaches
     * every one of them, because every one of them is an `ORMap`.
     */
    @Test
    fun theFixRecursesIntoANestedObject() {
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
                assertEquals(
                    x.piece(y).piece(z),
                    x.piece(y.piece(z)),
                    "the two groupings must agree — nested-ORMap case",
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
                    setOf<JsonValue>(JsonValue.Str("p2")),
                    (rightInner as? JsonNode.Leaf)?.register?.values,
                    "x⊔(y⊔z), one level down: the inner removed write must not come back",
                )
            },
        )
    }

    /**
     * The value position is a **merging lattice of its own** — an [Rga] — so the resurrection used
     * to land *inside* the sequence: the replacement array grew an element that a `remove` had
     * already discarded, in one grouping and not the other. Pinned separately because a fix that
     * only reached scalar leaves would still be wrong here.
     */
    @Test
    fun aRemovedArrayElementStaysGoneInEveryGrouping() {
        val first = JsonCrdt.empty(a).set("xs", arrayNode("W1", scalar("W1", "e1")))
        val removed = first.remove("xs")
        val reSet = removed.set("xs", arrayNode("W2", scalar("W2", "e2")))

        val left = (first.piece(removed).piece(reSet)["xs"] as? JsonNode.Array)?.rga
        val right = (first.piece(removed.piece(reSet))["xs"] as? JsonNode.Array)?.rga

        assertAll(
            { assertEquals(left, right, "the two groupings must agree — Rga-valued case") },
            { assertEquals(1, left?.size, "(x⊔y)⊔z: the removed array stays gone") },
            { assertEquals(1, right?.size, "x⊔(y⊔z): …and does not rejoin the replacement here either") },
        )
    }

    /**
     * A *concurrent* set and remove, which is where the semantics the fix settles are visible.
     *
     * **Add-wins holds on the key**: the setter's write is tagged with a dot the remover never saw,
     * so the key is present under every grouping. What it holds is that write **alone**. `v1` hangs
     * off the tag the remover retired and goes with it — the remover observed `v1` and removed it,
     * and the setter added `v2` rather than re-adding `v1`.
     *
     * This assertion read `{v1, v2}` before #2086, because a `set` on a state that still held the
     * key merged the old value into the new one locally, past the reach of any later remove. That
     * carrying-along is precisely the blend that made the bracketing matter, so it could not be
     * kept and the law recovered at the same time.
     */
    @Test
    fun pieceIsAssociativeAcrossAConcurrentSetAndRemove() {
        val start = JsonCrdt.empty(a).set("k", scalar("W1", "v1"))
        val remover = start.remove("k")
        val setter = start.withReplica(b).set("k", scalar("W2", "v2"))

        assertAll(
            *associativityChecks("concurrent set/remove", remover, setter, start),
            {
                assertContains(
                    remover.piece(setter).piece(start).keys,
                    "k",
                    "add-wins: the concurrent write survives the remove",
                )
            },
            {
                assertEquals(
                    setOf<JsonValue>(JsonValue.Str("v2")),
                    remover.piece(setter).piece(start).scalarsAt("k"),
                    "…holding its own write alone — v1 sat on the tag the remover retired",
                )
            },
        )
    }

    /**
     * Cross-type precedence (`Object > Array > Leaf`) with all three types **concurrent at one key
     * and descended from a shared ancestor**, which [crossTypePieceIsAssociative] above does not
     * reach: it folds its three documents from three independent `empty()`s, so no state there is
     * an ancestor of another.
     *
     * The branch point deliberately leaves `k` absent and carries an unrelated key instead. Writing
     * `k` on a state that *already holds* it would collapse the types at write time — [ORMap.put] is
     * additive, so `set` runs `Leaf.piece(Array)` locally and the state stops being purely typed —
     * and the triple would no longer put three different types side by side at merge time.
     *
     * `Object > Array > Leaf` is a total order and `max` over one is associative. This is the test
     * that says so; a tiebreak that answered by receiver rather than by rank would break it.
     */
    @Test
    fun pieceIsAssociativeOnConcurrentCrossTypeWritesFromASharedAncestor() {
        val c = ReplicaId("C")
        val branchPoint = JsonCrdt.empty(a).set("unrelated", scalar("W0", "v0"))
        val asLeaf = branchPoint.set("k", scalar("W1", "v1"))
        val asArray = branchPoint.withReplica(b).set("k", arrayNode("W2", scalar("W2", "e1")))
        val asObject = branchPoint.withReplica(c).set("k", objectNode("W3", "p" to scalar("W3", "p1")))

        assertAll(
            *associativityChecks("concurrent cross-type", asLeaf, asArray, asObject),
            {
                assertIs<JsonNode.Array>(asLeaf["k"]?.piece(asArray["k"]!!), "vacuity: pure types")
            },
            {
                assertIs<JsonNode.Object>(
                    asLeaf.piece(asArray).piece(asObject)["k"],
                    "the richest type must win regardless of grouping",
                )
            },
            {
                assertContains(
                    asLeaf.piece(asArray).piece(asObject).keys,
                    "unrelated",
                    "the shared ancestor's key must survive every grouping",
                )
            },
        )
    }

    /**
     * **The general law over causally-related trajectories, run twice — and the residual
     * measurement that makes the fix credible.**
     *
     * A fix validated on a named counterexample looks complete and is not. This test was written
     * against the broken `ORMap` and measured **280 violations in 39,232 triples** with `remove` in
     * the generator, against 0 with it taken out. Both arms are now **0**, over the same seed and
     * the same triples, which is the claim worth making: not "the counterexample passes" but "no
     * randomly-drawn ancestor triple diverges at all".
     *
     * The distinction matters because a partial fix scores well here. A prototype that gated
     * `ORMapEntry.join`'s value merge on which side's tags survived — the obvious repair — removed
     * only about a tenth of the violations, leaving 0.73% on `JsonCrdt` and 0.85% on a bare
     * `ORMap<String, GSet<String>>` against 0.96% unfixed. Nothing but a residual count separates
     * that from a real fix.
     *
     * The control arm keeps its own value: it pins that no part of `JsonNode`'s own algebra (the
     * type precedence rule, `Rga.piece`, `MVRegister.piece`) was ever non-associative, so an
     * `ORMap`-level fix is the whole fix. The four vacuity guards below are what stop both arms
     * reading zero because the generator stopped generating.
     *
     * This is the coverage the existing surfaces cannot give. [pieceIsAssociative] and
     * [crossTypePieceIsAssociative] fold each of their three documents from a separate
     * `JsonCrdt.empty()`, so no state is ever a causal ancestor of another and no dot is ever
     * superseded across the triple — and neither uses `remove` at all.
     */
    @Test
    fun pieceIsAssociativeOverCausallyRelatedTrajectories() {
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
                assertEquals(
                    0,
                    withRemoves.violations,
                    "#2086: ${withRemoves.violations} of ${withRemoves.triples} triples diverge with " +
                        "removes in the generator. This arm measured 280 before the fix; anything " +
                        "above zero is a residual, not a regression in something else.\n" +
                        withRemoves.firstViolation,
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
        const val TRAJECTORY_TRIALS = 35

        /**
         * Floors the generator must clear. **Measured on seed 2086, over 39,232 triples per arm:
         * the control made 172 overwrites of a live key, 57 object values and 63 array values; the
         * removes arm re-set a key some replica had already removed 22 times and found 280
         * violations.** Each floor sits near half its measurement, so an incidental tweak does not
         * red-light the suite while a generator that stopped writing structured values — or stopped
         * re-setting after a remove, the shape that breaks `ORMap` — fails loudly instead of
         * passing vacuously.
         */
        const val MIN_RE_SETS = 85
        const val MIN_STRUCTURED = 28
        const val MIN_RE_SETS_AFTER_REMOVE = 11

        val TRAJECTORY_REPLICAS = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))

        /** A small pool, so branches collide on the same key and states become genuine ancestors. */
        val TRAJECTORY_KEYS = listOf("k1", "k2")
    }
}
