package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlyIdTest {
    @Test
    fun valueIsPreservedAndEquality() {
        assertEquals(PlyId("relay"), PlyId("relay"))
        assertNotEquals(PlyId("relay"), PlyId("lan"))
        assertEquals("relay", PlyId("relay").value)
    }

    @Test
    fun soleIsAStableConstant() {
        assertEquals(PlyId.Sole, PlyId.Sole)
    }
}
