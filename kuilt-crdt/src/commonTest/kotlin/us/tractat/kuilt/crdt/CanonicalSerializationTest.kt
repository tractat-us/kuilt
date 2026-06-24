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
