package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MutableTraceContextHolderTest {
    private fun trace(tag: Byte) =
        ActiveTrace(ByteString(ByteArray(16) { tag }), ByteString(ByteArray(8) { tag }), sampled = true)

    @Test
    fun currentReflectsLastSet() {
        val holder = MutableTraceContextHolder()
        assertNull(holder.current())
        val t = trace(1)
        holder.set(t)
        assertEquals(t, holder.current())
        holder.set(null)
        assertNull(holder.current())
    }

    @Test
    fun honoursInitialValue() {
        val t = trace(2)
        assertEquals(t, MutableTraceContextHolder(t).current())
    }
}
