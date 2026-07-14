package us.tractat.kuilt.session.election

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElectHostTest {
    @Test
    fun `elects the lowest PeerId by value`() {
        val peers = setOf(PeerId("ccc"), PeerId("aaa"), PeerId("bbb"))
        assertEquals(PeerId("aaa"), electHost(peers))
    }

    @Test
    fun `single peer elects itself`() {
        assertEquals(PeerId("solo"), electHost(setOf(PeerId("solo"))))
    }

    @Test
    fun `empty set fails fast`() {
        assertFailsWith<IllegalArgumentException> { electHost(emptySet()) }
    }
}
