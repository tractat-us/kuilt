package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WithActiveTraceTest {
    private fun trace(tag: Byte) =
        ActiveTrace(ByteString(ByteArray(16) { tag }), ByteString(ByteArray(8) { tag }), sampled = true)

    @Test
    fun elementSetsSlotInsideAndRestoresOutside() = runTest {
        assertNull(currentActiveTrace())
        val t = trace(1)
        withActiveTrace(t) {
            // The synchronous read the capture edge performs must see `t` here.
            assertEquals(t, currentActiveTrace())
        }
        assertNull(currentActiveTrace())
    }

    @Test
    fun nestedScopesShadowAndRestore() = runTest {
        val outer = trace(1)
        val inner = trace(2)
        withActiveTrace(outer) {
            assertEquals(outer, currentActiveTrace())
            withActiveTrace(inner) {
                assertEquals(inner, currentActiveTrace())
            }
            assertEquals(outer, currentActiveTrace())
        }
        assertNull(currentActiveTrace())
    }
}
