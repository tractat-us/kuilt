package us.tractat.kuilt.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SeamPliesDefaultTest {
    @Test
    fun singlePlyFabricReportsOneEntryMapMatchingState() = runTest {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("host"))
        val plies = seam.plies.value
        assertEquals(1, plies.size, "single-ply fabric reports exactly one ply")
        assertEquals(seam.state.value, plies[PlyId.Sole], "the sole ply's state equals the aggregate")
    }
}
