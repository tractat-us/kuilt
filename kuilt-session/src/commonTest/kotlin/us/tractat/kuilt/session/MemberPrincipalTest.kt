package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class MemberPrincipalTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    @Test
    fun `admit carries the connection's verified principal onto the admitted member`() = runTest {
        val mesh = InMemoryLoom()
        val principal = Principal("device-123")

        // A loom that attests every hosted connection with a verified principal —
        // mirroring how a server loom attaches the authenticated call principal to
        // the connection. No out-of-band map: the principal rides the connection.
        val attestingLoom = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam {
                val seam = mesh.weave(rendezvous)
                return if (rendezvous is Rendezvous.New) seam.withPrincipal(principal) else seam
            }
        }

        val host = LoomRoomHost(attestingLoom, Pattern("server"), clock = zeroClock)
        val hostRoomReady = CompletableDeferred<Room>()
        val hostJob = backgroundScope.launch { host.start { room -> hostRoomReady.complete(room) } }
        val hostRoom = hostRoomReady.await()

        val joiner = SeamRoomFactory(mesh, backgroundScope, zeroClock).join(InMemoryTag("server"))

        hostRoom.roster.first { it.size == 1 }
        assertEquals(
            principal,
            hostRoom.roster.value.single().principal,
            "host must carry the verified principal onto the admitted member",
        )

        // The joiner did not verify the host, so its roster entry has no principal.
        joiner.roster.first { it.isNotEmpty() }
        assertNull(
            joiner.roster.value.single().principal,
            "joiner-side member carries no verified principal",
        )
        hostJob.cancel()
    }
}
