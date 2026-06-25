package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpRegistryTest {

    @Test
    fun registerAndResolveSameOp() {
        val registry = OpRegistry()
        val op = Op { args -> args }
        registry.register(OpId("echo"), op)
        assertTrue(op === registry.resolve(OpId("echo")), "Expected the exact Op instance that was registered")
    }

    @Test
    fun registeredSetReflectsAdds() {
        val registry = OpRegistry()
        registry.register(OpId("alpha"), Op { it })
        registry.register(OpId("beta"), Op { it })
        assertEquals(setOf(OpId("alpha"), OpId("beta")), registry.registered)
    }

    @Test
    fun duplicateRegisterThrows() {
        val registry = OpRegistry()
        registry.register(OpId("score"), Op { it })
        assertFailsWith<IllegalStateException> {
            registry.register(OpId("score"), Op { it })
        }
    }

    @Test
    fun resolveUnknownOpReturnsNull() {
        assertNull(OpRegistry().resolve(OpId("missing")))
    }

    @Test
    fun echoOpRunsAndReturnsSameBytes() = runTest {
        val registry = OpRegistry()
        registry.register(OpId("echo")) { args -> args }
        val op = assertNotNull(registry.resolve(OpId("echo")))
        val input = byteArrayOf(1, 2, 3)
        assertContentEquals(input, op.invoke(input))
    }
}
