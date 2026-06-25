@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
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
