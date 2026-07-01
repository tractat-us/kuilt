package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TraceContextTest {
    private fun bytes(n: Int) = ByteString(ByteArray(n) { it.toByte() })

    @Test
    fun activeTraceRequiresCorrectByteSizes() {
        val ok = ActiveTrace(bytes(16), bytes(8), sampled = true)
        assertEquals(16, ok.traceId.size)
        assertEquals(8, ok.spanId.size)
        assertFailsWith<IllegalArgumentException> { ActiveTrace(bytes(15), bytes(8), true) }
        assertFailsWith<IllegalArgumentException> { ActiveTrace(bytes(16), bytes(7), true) }
    }

    @Test
    fun providerIsAFunctionalInterface() {
        val trace = ActiveTrace(bytes(16), bytes(8), sampled = false)
        val provider = TraceContextProvider { trace }
        assertEquals(trace, provider.current())
        val empty = TraceContextProvider { null }
        assertNull(empty.current())
    }

    @Test
    fun captureConfigDefaultsToCaptureUntraced() {
        assertEquals(UntracedPolicy.CAPTURE, CaptureConfig().untracedPolicy)
    }
}
