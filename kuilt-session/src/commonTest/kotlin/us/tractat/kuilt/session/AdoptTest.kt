package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
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
}
