package us.tractat.kuilt.nw

import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun toStringRedactsTheBearerSecret() {
        val tag = NwTag(sessionName = "oyster-lobby", peerKey = "peer-7", roomKey = "swatch-oyster-42")
        val rendered = tag.toString()
        assertAll(
            // the secret must never appear in a stringified tag (logs / exceptions / debugger)
            { assertFalse(rendered.contains("swatch-oyster-42"), "roomKey leaked in toString: $rendered") },
            { assertTrue(rendered.contains("<redacted>")) },
            // non-secret fields still render (toString stays useful for diagnostics)
            { assertTrue(rendered.contains("oyster-lobby")) },
            { assertTrue(rendered.contains("peer-7")) },
        )
    }
}
