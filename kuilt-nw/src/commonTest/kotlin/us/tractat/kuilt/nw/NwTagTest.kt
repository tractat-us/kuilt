package us.tractat.kuilt.nw

import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class NwTagTest {

    @Test
    fun fieldsPlumbThrough() {
        val tag = NwTag(sessionName = "oyster-lobby", peerKey = "peer-7", roomKey = "swatch-oyster-42")
        assertAll(
            { assertEquals("oyster-lobby", tag.sessionName) },
            { assertEquals("peer-7", tag.peerKey) },
            { assertEquals("swatch-oyster-42", tag.roomKey) },
        )
    }

    @Test
    fun usableWhereTagIsExpected() {
        val tag: Tag = NwTag(sessionName = "oyster-lobby", peerKey = "peer-7", roomKey = "swatch-oyster-42")
        // roomKey is required non-null on this fabric — it reads back through the base contract.
        assertEquals("swatch-oyster-42", tag.roomKey)
    }
}
