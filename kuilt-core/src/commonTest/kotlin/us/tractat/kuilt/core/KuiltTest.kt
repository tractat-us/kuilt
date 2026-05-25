package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals

class KuiltTest {
    @Test
    fun marker_identifies_the_library() {
        assertEquals("kuilt-core", Kuilt.MODULE)
    }
}
