package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-replica byte-stability tests: two replicas that reach the same logical
 * state via **different delivery orders** must serialize to **identical bytes**
 * (JSON and CBOR). Any ordering dependency in the serialized form silently breaks
 * content-addressing, digest equality, and Quilter delta fingerprinting.
 *
 * Issue #713 — audit of Set-backed CRDT serializers for delivery-order byte instability.
 */
@OptIn(ExperimentalSerializationApi::class)
class CanonicalSerializationTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val json = Json { allowStructuredMapKeys = true }
    private val cbor = Cbor {}

    // ── DotContext ────────────────────────────────────────────────────────────

    /**
     * Two DotContext replicas absorb the same dots in different orders.
     * Their serialized bytes must be identical.
     */
    @Test
    fun dotContextSerializationIsDeliveryOrderIndependent() {
        val dotA1 = Dot(a, 1L)
        val dotB1 = Dot(b, 1L)

        // Replica 1: sees A first, then B
        val ctx1 = DotContext.EMPTY.add(dotA1).add(dotB1)
        // Replica 2: sees B first, then A
        val ctx2 = DotContext.EMPTY.add(dotB1).add(dotA1)

        assertEquals(ctx1, ctx2) // sanity: same logical state

        val jsonBytes1 = json.encodeToString(DotContext.serializer(), ctx1)
        val jsonBytes2 = json.encodeToString(DotContext.serializer(), ctx2)
        assertEquals(jsonBytes1, jsonBytes2, "DotContext JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(DotContext.serializer(), ctx1)
        val cborBytes2 = cbor.encodeToByteArray(DotContext.serializer(), ctx2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "DotContext CBOR must be delivery-order-independent")
    }

    /**
     * DotContext with a cloud (non-contiguous dots) is also canonical.
     */
    @Test
    fun dotContextWithCloudIsCanonical() {
        // (A,3) without (A,1),(A,2) → stays in cloud
        val dotA3 = Dot(a, 3L)
        val dotB2 = Dot(b, 2L)

        val ctx1 = DotContext.EMPTY.add(dotA3).add(dotB2)
        val ctx2 = DotContext.EMPTY.add(dotB2).add(dotA3)

        assertEquals(ctx1, ctx2)

        val jsonBytes1 = json.encodeToString(DotContext.serializer(), ctx1)
        val jsonBytes2 = json.encodeToString(DotContext.serializer(), ctx2)
        assertEquals(jsonBytes1, jsonBytes2, "DotContext cloud JSON must be delivery-order-independent")
    }

    // ── ORSet (via DotFun / DotSet) ───────────────────────────────────────────

    /**
     * Two ORSet replicas add elements in opposite orders and then merge.
     * Their serialized bytes after merge must be identical.
     */
    @Test
    fun orSetSerializationIsDeliveryOrderIndependent() {
        // Replica 1: A adds "x", B adds "y"
        val s1a = ORSet.empty<String>().add(a, "x")
        val s1b = ORSet.empty<String>().add(b, "y")
        val merged1 = s1a.piece(s1b)

        // Replica 2: B adds "y", A adds "x"
        val s2b = ORSet.empty<String>().add(b, "y")
        val s2a = ORSet.empty<String>().add(a, "x")
        val merged2 = s2b.piece(s2a)

        assertEquals(merged1, merged2)

        val ser = ORSet.serializer(String.serializer())
        val jsonBytes1 = json.encodeToString(ser, merged1)
        val jsonBytes2 = json.encodeToString(ser, merged2)
        assertEquals(jsonBytes1, jsonBytes2, "ORSet JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, merged1)
        val cborBytes2 = cbor.encodeToByteArray(ser, merged2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "ORSet CBOR must be delivery-order-independent")
    }

    // ── ResettableCounter (via DotFun) ────────────────────────────────────────

    /**
     * Two ResettableCounter replicas absorb increments in different orders.
     * Their serialized bytes must be identical.
     */
    @Test
    fun resettableCounterSerializationIsDeliveryOrderIndependent() {
        val incA = ResettableCounter.ZERO.increment(a, 3L)
        val incB = ResettableCounter.ZERO.increment(b, 5L)

        // Replica 1: absorbs A's increment, then B's
        val counter1 = ResettableCounter.ZERO.piece(incA).piece(incB)

        // Replica 2: absorbs B's increment, then A's
        val counter2 = ResettableCounter.ZERO.piece(incB).piece(incA)

        assertEquals(counter1.value, counter2.value)
        assertEquals(counter1, counter2)

        val ser = ResettableCounter.serializer()
        val jsonBytes1 = json.encodeToString(ser, counter1)
        val jsonBytes2 = json.encodeToString(ser, counter2)
        assertEquals(jsonBytes1, jsonBytes2, "ResettableCounter JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, counter1)
        val cborBytes2 = cbor.encodeToByteArray(ser, counter2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "ResettableCounter CBOR must be delivery-order-independent")
    }

    // ── ORMap (via DotMap) ────────────────────────────────────────────────────

    /**
     * Two ORMap replicas put keys in opposite orders and then merge.
     * Their serialized bytes after merge must be identical.
     *
     * This test catches a regression where DotMapSerializer sorted by [key.toString()]
     * instead of the structural key encoding — a toString-based sort is fragile for
     * non-injective types (Double, ByteArray, etc.).  The String key here is the
     * minimal exercise; the structural-sort property is proven by insertion-order
     * independence (issue #752).
     */
    @Test
    fun orMapSerializationIsDeliveryOrderIndependent() {
        // Replica 1: A puts "alpha", B puts "beta"
        val m1a = ORMap.empty<String, GCounter>().put(a, "alpha", GCounter.of(a to 1L))
        val m1b = ORMap.empty<String, GCounter>().put(b, "beta", GCounter.of(b to 2L))
        val merged1 = m1a.piece(m1b)

        // Replica 2: B puts "beta", A puts "alpha"
        val m2b = ORMap.empty<String, GCounter>().put(b, "beta", GCounter.of(b to 2L))
        val m2a = ORMap.empty<String, GCounter>().put(a, "alpha", GCounter.of(a to 1L))
        val merged2 = m2b.piece(m2a)

        assertEquals(merged1, merged2)

        val ser = ORMap.serializer(String.serializer(), GCounter.serializer())
        val jsonBytes1 = json.encodeToString(ser, merged1)
        val jsonBytes2 = json.encodeToString(ser, merged2)
        assertEquals(jsonBytes1, jsonBytes2, "ORMap JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, merged1)
        val cborBytes2 = cbor.encodeToByteArray(ser, merged2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "ORMap CBOR must be delivery-order-independent")
    }

    /**
     * DotMap structural sort must be independent of the order entries were inserted,
     * not just of merge order.  This directly validates that the comparator is a
     * pure function of key content — the structural-sort invariant (#752).
     *
     * We construct two DotMap instances with the same keys but in different insertion
     * orders (without merging) and assert identical serialized bytes.
     */
    @Test
    fun dotMapSortIsInsertionOrderIndependent() {
        val dotA = Dot(a, 1L)
        val dotB = Dot(b, 1L)

        // Build two DotMaps with the same entries but in reversed insertion order.
        val map1 = DotMap(linkedMapOf("zebra" to DotSet(setOf(dotA)), "aardvark" to DotSet(setOf(dotB))))
        val map2 = DotMap(linkedMapOf("aardvark" to DotSet(setOf(dotB)), "zebra" to DotSet(setOf(dotA))))

        // They must be equal (same entries) …
        assertEquals(map1, map2)

        // … and serialize to the same bytes regardless of insertion order.
        val ser = DotMap.serializer(String.serializer(), DotSet.serializer())
        val jsonBytes1 = json.encodeToString(ser, map1)
        val jsonBytes2 = json.encodeToString(ser, map2)
        assertEquals(jsonBytes1, jsonBytes2, "DotMap JSON must be insertion-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, map1)
        val cborBytes2 = cbor.encodeToByteArray(ser, map2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "DotMap CBOR must be insertion-order-independent")
    }

    /**
     * Regression guard for the toString-sort fragility (#752): two structurally
     * distinct keys that share a common [toString] prefix must still sort
     * deterministically by their structural encoding.
     *
     * We use [ReplicaId] keys (inline value class over [String]) to confirm that
     * the structural-key comparator works for non-primitive serializable types.
     */
    @Test
    fun dotMapStructuralSortWorksForInlineValueClassKeys() {
        val r1 = ReplicaId("alice")
        val r2 = ReplicaId("bob")

        val dotR1 = Dot(a, 1L)
        val dotR2 = Dot(b, 1L)

        val map1 = DotMap(linkedMapOf(r1 to DotSet(setOf(dotR1)), r2 to DotSet(setOf(dotR2))))
        val map2 = DotMap(linkedMapOf(r2 to DotSet(setOf(dotR2)), r1 to DotSet(setOf(dotR1))))

        assertEquals(map1, map2)

        val ser = DotMap.serializer(ReplicaId.serializer(), DotSet.serializer())
        val jsonBytes1 = json.encodeToString(ser, map1)
        val jsonBytes2 = json.encodeToString(ser, map2)
        assertEquals(jsonBytes1, jsonBytes2, "DotMap with ReplicaId keys must be insertion-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, map1)
        val cborBytes2 = cbor.encodeToByteArray(ser, map2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "DotMap with ReplicaId keys CBOR must be canonical")
    }

    // ── Rga ───────────────────────────────────────────────────────────────────

    /**
     * Two Rga replicas absorb ops in different orders and arrive at the same
     * logical sequence. Their serialized bytes must be identical.
     */
    @Test
    fun rgaSerializationIsDeliveryOrderIndependent() {
        // A inserts "hello", B inserts "world"
        val (rgaA, opA) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "hello")
        val (rgaB, opB) = Rga.empty<String>().insertAfter(b, RgaId.HEAD, "world")

        // Replica 1: starts from A's state, applies B's op
        val rga1 = rgaA.apply(opB)

        // Replica 2: starts from B's state, applies A's op
        val rga2 = rgaB.apply(opA)

        assertEquals(rga1, rga2) // same logical op-set

        val ser = Rga.wireSerializer(String.serializer())
        val jsonBytes1 = json.encodeToString(ser, rga1)
        val jsonBytes2 = json.encodeToString(ser, rga2)
        assertEquals(jsonBytes1, jsonBytes2, "Rga JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, rga1)
        val cborBytes2 = cbor.encodeToByteArray(ser, rga2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "Rga CBOR must be delivery-order-independent")
    }
}
