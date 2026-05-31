package us.tractat.kuilt.core.composite

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompositeRollupTest {
    @Test
    fun aggregateIsWovenWhenAnyPlyIsWovenAndPliesListsBoth() = runTest {
        val loom = CompositeLoom(
            listOf(PlyId("a") to InMemoryLoom(), PlyId("b") to InMemoryLoom()),
        )
        val seam = loom.host(Pattern("host"))
        assertIs<SeamState.Woven>(seam.state.first { it is SeamState.Woven })
        assertEquals(setOf(PlyId("a"), PlyId("b")), seam.plies.value.keys)
    }

    @Test
    fun closeDrivesAggregateTorn() = runTest {
        val loom = CompositeLoom(listOf(PlyId("a") to InMemoryLoom()))
        val seam = loom.host(Pattern("host"))
        seam.close()
        assertIs<SeamState.Torn>(seam.state.value)
    }
}
