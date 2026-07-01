package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OtelSdkTraceContextProviderTest {
    private val provider = OtelSdkTraceContextProvider()

    @Test
    fun invalidContextMapsToNull() {
        assertNull(provider.fromSpanContext(SpanContext.getInvalid()))
    }

    @Test
    fun sampledContextMapsWithBytesAndFlag() {
        val ctx = SpanContext.create(
            "0102030405060708090a0b0c0d0e0f10",
            "1112131415161718",
            TraceFlags.getSampled(),
            TraceState.getDefault(),
        )
        val trace = assertNotNull(provider.fromSpanContext(ctx))
        assertEquals(16, trace.traceId.size)
        assertEquals(8, trace.spanId.size)
        assertEquals(true, trace.sampled)
        assertEquals(ByteString(*ctx.traceIdBytes), trace.traceId)
        assertEquals(ByteString(*ctx.spanIdBytes), trace.spanId)
    }

    @Test
    fun unsampledContextMapsWithFlagFalse() {
        val ctx = SpanContext.create(
            "0102030405060708090a0b0c0d0e0f10",
            "1112131415161718",
            TraceFlags.getDefault(),
            TraceState.getDefault(),
        )
        assertEquals(false, assertNotNull(provider.fromSpanContext(ctx)).sampled)
    }
}
