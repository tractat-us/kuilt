package us.tractat.kuilt.otel.logging

import java.lang.reflect.ParameterizedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Checks the compiled API, including the batch element type erased at runtime. */
class ResolvedLogEventApiTest {
    @Test
    fun queuedCaptureRequiresResolvedInput() {
        val methods = LogCapture::class.java.methods.filter { it.name == "capture" }
        assertEquals(1, methods.size)
        assertEquals("us.tractat.kuilt.otel.logging.ResolvedLogEvent", methods.single().parameterTypes.first().name)
    }

    @Test
    fun queuedBatchRequiresResolvedInput() {
        val method = LogCapture::class.java.methods.single { it.name == "captureAll" }
        val input = method.genericParameterTypes.first() as ParameterizedType
        assertFalse(input.actualTypeArguments.single().typeName.contains("NormalizedLogEvent"))
        assertEquals(true, input.actualTypeArguments.single().typeName.contains("ResolvedLogEvent"))
    }

    @Test
    fun resolvedEventsHaveNoPublicConstructorOrCopy() {
        val type = ResolvedLogEvent::class.java
        us.tractat.kuilt.test.assertAll(
            { assertFalse(type.constructors.any { !it.isSynthetic }) },
            { assertFalse(type.methods.any { it.name == "copy" }) },
        )
    }
}
