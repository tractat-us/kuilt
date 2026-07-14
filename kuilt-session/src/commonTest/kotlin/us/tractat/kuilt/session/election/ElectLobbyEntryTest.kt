package us.tractat.kuilt.session.election

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.SeamRoomFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ElectLobbyEntryTest {
    @Test
    fun `electLobby weaves a mesh and returns a lobby that elects self when alone`() =
        runTest {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })
            val lobby = factory.electLobby(Pattern("game"))
            // Alone on the mesh → this peer is the elected host.
            assertEquals(lobby.selfId, lobby.host.first())
            lobby.leave()
        }
}
