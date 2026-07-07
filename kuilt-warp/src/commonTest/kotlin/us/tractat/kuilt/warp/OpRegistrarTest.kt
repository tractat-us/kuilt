package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpRegistrarTest {

    @Test
    fun shuttleReturnsTheOpUnchanged() {
        val op = Op { args -> args }
        assertTrue(shuttle(op) === op, "shuttle is declaration-site sugar — it must return the exact Op")
    }

    @Test
    fun shuttleDeclaredOpRunsItsBody() = runTest {
        val reverse = shuttle { args -> args.reversedArray() }
        assertContentEquals(byteArrayOf(3, 2, 1), reverse.invoke(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun opRegistryOfInstallsEveryRegistrar() {
        val registry = opRegistryOf(
            OpRegistrar { it.register(OpId("alpha"), Op { args -> args }) },
            OpRegistrar { it.register(OpId("beta"), Op { args -> args }) },
        )
        assertEquals(setOf(OpId("alpha"), OpId("beta")), registry.registered)
    }

    @Test
    fun opRegistryOfWithNoRegistrarsIsEmpty() {
        assertEquals(emptySet(), opRegistryOf().registered)
    }

    @Test
    fun registrarsSharingAnOpIdFailLoud() {
        val alpha = OpRegistrar { it.register(OpId("alpha"), Op { args -> args }) }
        assertFailsWith<IllegalStateException> { opRegistryOf(alpha, alpha) }
    }

    @Test
    fun installedOpResolvesAndRuns() = runTest {
        val registry = opRegistryOf(
            OpRegistrar { it.register(OpId("reverse"), shuttle { args -> args.reversedArray() }) },
        )
        val op = assertNotNull(registry.resolve(OpId("reverse")))
        assertContentEquals(byteArrayOf(9, 8), op.invoke(byteArrayOf(8, 9)))
    }
}
