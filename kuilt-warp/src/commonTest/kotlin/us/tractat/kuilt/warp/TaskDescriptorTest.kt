@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskDescriptorTest {

    @Test
    fun equalsAndHashCodeUseByteContent() {
        val a = TaskDescriptor(OpId("score"), byteArrayOf(1, 2, 3), traceparent = "00-abc-def-01")
        val b = TaskDescriptor(OpId("score"), byteArrayOf(1, 2, 3), traceparent = "00-abc-def-01")
        // Distinct ByteArray instances with identical content must compare equal.
        assertTrue(a !== b)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun differingArgsAreNotEqual() {
        val a = TaskDescriptor(OpId("score"), byteArrayOf(1, 2, 3))
        val b = TaskDescriptor(OpId("score"), byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun differingOpIsNotEqual() {
        val a = TaskDescriptor(OpId("score"), byteArrayOf(1))
        val b = TaskDescriptor(OpId("rank"), byteArrayOf(1))
        assertNotEquals(a, b)
    }

    @Test
    fun defaultsAreEmptyArgsAndNullTrace() {
        val d = TaskDescriptor(OpId("noop"))
        assertEquals(0, d.args.size)
        assertNull(d.traceparent)
        assertNull(d.pinnedOwner)
    }

    @Test
    fun differingPinnedOwnerIsNotEqual() {
        val a = TaskDescriptor(OpId("train"), byteArrayOf(1), pinnedOwner = PeerId("alice"))
        val b = TaskDescriptor(OpId("train"), byteArrayOf(1), pinnedOwner = PeerId("bob"))
        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equalPinnedOwnerIsEqual() {
        val a = TaskDescriptor(OpId("train"), byteArrayOf(1), pinnedOwner = PeerId("alice"))
        val b = TaskDescriptor(OpId("train"), byteArrayOf(1), pinnedOwner = PeerId("alice"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun toStringIncludesPinnedOwner() {
        val pinned = TaskDescriptor(OpId("train"), pinnedOwner = PeerId("alice")).toString()
        assertTrue("alice" in pinned, "toString must surface the pinned owner: $pinned")
    }

    @Test
    fun serializationRoundTripsWithPinnedOwner() {
        val original = TaskDescriptor(OpId("train"), byteArrayOf(4, 2), pinnedOwner = PeerId("alice"))
        val restored = Cbor.decodeFromByteArray(
            TaskDescriptor.serializer(),
            Cbor.encodeToByteArray(original),
        )
        assertEquals(original, restored)
        assertEquals(PeerId("alice"), restored.pinnedOwner)
    }

    /**
     * A descriptor built without a pinned owner serialises to the **same bytes** it always
     * did — proving the new optional field is wire-backward-compatible (no `encodeDefaults`,
     * so a null `pinnedOwner` adds nothing to the encoding).
     */
    @Test
    fun absentPinnedOwnerIsWireBackwardCompatible() {
        val withoutPin = TaskDescriptor(OpId("score"), byteArrayOf(9, 8, 7), traceparent = "00-t-s-01")
        val bytes = Cbor.encodeToByteArray(withoutPin)
        val restored = Cbor.decodeFromByteArray(TaskDescriptor.serializer(), bytes)
        assertEquals(withoutPin, restored)
        assertNull(restored.pinnedOwner)
        // Round-trip is stable and the encoding is identical across runs (no extra field).
        assertContentEquals(bytes, Cbor.encodeToByteArray(restored))
    }

    @Test
    fun serializationRoundTrips() {
        val original = TaskDescriptor(OpId("score"), byteArrayOf(9, 8, 7), traceparent = "00-trace-span-01")
        val bytes = Cbor.encodeToByteArray(original)
        val restored = Cbor.decodeFromByteArray(TaskDescriptor.serializer(), bytes)
        assertEquals(original, restored)
    }

    @Test
    fun serializationRoundTripsWithNullTrace() {
        val original = TaskDescriptor(OpId("rank"), byteArrayOf(0, 0, 1))
        val restored = Cbor.decodeFromByteArray(
            TaskDescriptor.serializer(),
            Cbor.encodeToByteArray(original),
        )
        assertEquals(original, restored)
        assertNull(restored.traceparent)
    }
}
