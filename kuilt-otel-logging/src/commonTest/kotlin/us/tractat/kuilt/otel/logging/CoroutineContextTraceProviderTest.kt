package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoroutineContextTraceProviderTest {
    private val provider = CoroutineContextTraceProvider()
    private fun trace() =
        ActiveTrace(ByteString(ByteArray(16) { 3 }), ByteString(ByteArray(8) { 4 }), sampled = true)

    @Test
    fun currentReflectsTheActiveScope() = runTest {
        assertNull(provider.current())
        val t = trace()
        withActiveTrace(t) {
            assertEquals(t, provider.current())
        }
        assertNull(provider.current())
    }
}
