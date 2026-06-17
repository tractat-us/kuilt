package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeLoomFlowCtorTest {

    @Test
    fun flowConstructorWeavesTheInitialDesiredSet() = runTest {
        val desired = MutableStateFlow(listOf(PlyId("mem") to InMemoryLoom() as Loom))
        val loom = CompositeLoom(desired, UnconfinedTestDispatcher())

        val seam = loom.host(Pattern("host"))

        assertIs<SeamState.Woven>(seam.state.value, "single in-memory ply is woven immediately")
        assertEquals(setOf(PlyId("mem")), seam.plies.value.keys)

        seam.close()
    }

    @Test
    fun availabilityReflectsCurrentDesiredSet() {
        val desired = MutableStateFlow(listOf(PlyId("mem") to InMemoryLoom() as Loom))
        val loom = CompositeLoom(desired, UnconfinedTestDispatcher())

        assertEquals(FabricAvailability.Available, loom.availability())
    }
}
