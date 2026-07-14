package us.tractat.kuilt.session.election

import us.tractat.kuilt.session.admit.AdmitMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyMessageTest {
    @Test
    fun `round-trips every variant`() {
        val messages = listOf(
            LobbyMessage.Freeze(hostId = "aaa", roster = setOf("aaa", "bbb"), epoch = 7L),
            LobbyMessage.FreezeAck(hostId = "aaa", epoch = 7L),
            LobbyMessage.Commit(hostId = "aaa", epoch = 7L),
            LobbyMessage.Reopen(epoch = 7L),
        )
        for (m in messages) {
            assertEquals(m, LobbyMessage.decode(LobbyMessage.encode(m)))
        }
    }

    @Test
    fun `decode rejects a non-lobby frame`() {
        assertNull(LobbyMessage.decode(byteArrayOf(0x00, 0x01)))
        // An admit frame must not decode as a lobby frame (distinct prefixes).
        assertNull(LobbyMessage.decode(AdmitMessage.encode(AdmitMessage.Goodbye)))
    }

    @Test
    fun `lobby and admit prefixes differ`() {
        assertTrue(LobbyMessage.PREFIX_BYTE != AdmitMessage.PREFIX_BYTE)
        assertTrue(LobbyMessage.isLobbyFrame(LobbyMessage.encode(LobbyMessage.Reopen(1L))))
    }
}
