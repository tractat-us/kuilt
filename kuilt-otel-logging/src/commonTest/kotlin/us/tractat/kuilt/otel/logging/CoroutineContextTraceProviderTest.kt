package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoroutineContextTraceProviderTest {
    private val provider = CoroutineContextTraceProvider()
    private fun trace() =
        ActiveTrace(ByteString(ByteArray(16) { 3 }), ByteString(ByteArray(8) { 4 }), sampled = true)

    // The Apple active-trace slot is a `@ThreadLocal` that outlives a single test; a
    // dirty slot would make the null assertions below fail on native. Clear it defensively.
    @AfterTest fun clear() { setActiveTrace(null) }

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
