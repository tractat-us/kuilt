package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.session.election.LobbyMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SeamRoomFactory.adopt] wraps an ALREADY-WOVEN [us.tractat.kuilt.core.Seam] into a [Room]
 * with an explicit role — no re-weave. This is the primitive the election lobby adopts with.
 */
class AdoptTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    @Test
    fun `adopt forms roster over pre-woven seams`() =
        runTest {
            val loom = InMemoryLoom()
            // Weave the mesh ourselves (what the lobby will do), then adopt with explicit roles.
            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))

            val f = factory(loom, backgroundScope)
            val hostRoom = f.adopt(hostSeam, SessionRole.Host, memberName = "Alice")
            val joinerRoom = f.adopt(joinerSeam, SessionRole.Joiner, memberName = "Bob")

            val hostRoster = hostRoom.roster.first { it.size == 1 }
            val joinerRoster = joinerRoom.roster.first { it.size == 1 }

            assertAll(
                { assertEquals(SessionRole.Host, hostRoom.role.value) },
                { assertEquals(SessionRole.Joiner, joinerRoom.role.value) },
                { assertEquals("Bob", hostRoster.first().identity.displayName) },
                { assertEquals("Alice", joinerRoster.first().identity.displayName) },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    /**
     * A freeze-round tail frame (prefix `0x65`) that crosses the adopt boundary — a broadcast
     * `FreezeAck` or late `Reopen` arriving after this peer received `Commit` and adopted — must be
     * dropped by [SeamRoom], not surfaced on [Room.incoming] as a bogus application frame (#1439).
     *
     * Determinism: both frames are broadcast from the same sender, so [InMemoryLoom] delivers them
     * FIFO — the stray lobby frame reaches the room's dispatch *before* the real app frame. If the
     * lobby frame were not dropped it would be the first frame delivered to [incoming]; asserting the
     * first delivered frame is the app frame proves it was dropped.
     */
    @Test
    fun `adopted room drops a stray lobby frame instead of surfacing it on incoming`() =
        runTest {
            val loom = InMemoryLoom()
            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))

            val f = factory(loom, backgroundScope)
            val hostRoom = f.adopt(hostSeam, SessionRole.Host, memberName = "Alice")
            val joinerRoom = f.adopt(joinerSeam, SessionRole.Joiner, memberName = "Bob")

            // Handshake completes: the joiner has admitted the host, so an app frame from the host
            // would route to incoming (an unadmitted sender would be dropped for a different reason).
            joinerRoom.roster.first { it.size == 1 }

            // Subscribe (UNDISPATCHED, so the collect is live) before broadcasting.
            val firstFrame = async(start = CoroutineStart.UNDISPATCHED) { joinerRoom.incoming.first() }

            val strayLobby = LobbyMessage.encode(LobbyMessage.Commit(hostSeam.selfId.value, epoch = 1L))
            val appFrame = byteArrayOf(0x01, 0x02, 0x03)
            hostSeam.broadcast(strayLobby)
            hostSeam.broadcast(appFrame)

            // The stray lobby frame is dropped; the first (and only) frame surfaced is the app frame.
            assertEquals(appFrame.toList(), firstFrame.await().payload.toList())

            joinerRoom.leave()
            hostRoom.leave()
        }
}
