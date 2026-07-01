package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveTraceSlotTest {
    private fun trace(tag: Byte) =
        ActiveTrace(ByteString(ByteArray(16) { tag }), ByteString(ByteArray(8) { tag }), sampled = true)

    @AfterTest fun clear() { setActiveTrace(null) }

    @Test
    fun slotDefaultsToNull() {
        assertNull(currentActiveTrace())
    }

    @Test
    fun setReturnsPriorAndUpdates() {
        val t1 = trace(1)
        assertNull(setActiveTrace(t1)) // prior was null
        assertEquals(t1, currentActiveTrace())
        val t2 = trace(2)
        assertEquals(t1, setActiveTrace(t2)) // prior was t1
        assertEquals(t2, currentActiveTrace())
        assertEquals(t2, setActiveTrace(null)) // prior was t2
        assertNull(currentActiveTrace())
    }
}
