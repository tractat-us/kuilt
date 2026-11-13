package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A joiner's `deviceId` reaches the host (#3208). Until `join` and `adopt` took one, nothing could
 * send it: the `Hello` field existed and every joiner left it null.
 */
class JoinDeviceIdTest {
    private val clock = { Instant.fromEpochMilliseconds(0L) }

    @Test
    fun helloCarriesTheSuppliedDeviceId() =
        runTest {
            val loom = InMemoryLoom()
            val host = SeamRoomFactory(loom, backgroundScope, clock, InMemoryDeviceKeyStore()).host(Pattern("Alice"))
            val withId = SeamRoomFactory(loom, backgroundScope, clock, InMemoryDeviceKeyStore())
                .join(InMemoryTag("Alice"), memberName = "Bob", deviceId = "d-1")
            val withoutId = SeamRoomFactory(loom, backgroundScope, clock, InMemoryDeviceKeyStore())
                .join(InMemoryTag("Alice"), memberName = "Carol")

            val roster = host.roster.first { it.size == 2 }.associateBy { it.identity.displayName }

            assertAll(
                { assertEquals("d-1", roster.getValue("Bob").identity.deviceId) },
                { assertNull(roster.getValue("Carol").identity.deviceId) },
            )

            withoutId.leave()
            withId.leave()
            host.leave()
        }

    @Test
    fun adoptedJoinerSendsItsDeviceId() =
        runTest {
            val loom = InMemoryLoom()
            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))
            // Two devices, so two factories: one store would make the host and the joiner one device.
            val hostFactory = SeamRoomFactory(loom, backgroundScope, clock, InMemoryDeviceKeyStore())
            val joinerFactory = SeamRoomFactory(loom, backgroundScope, clock, InMemoryDeviceKeyStore())

            val hostRoom = hostFactory.adopt(hostSeam, SessionRole.Host, memberName = "Alice")
            val joinerRoom = joinerFactory.adopt(joinerSeam, SessionRole.Joiner, memberName = "Bob", deviceId = "d-2")

            assertEquals("d-2", hostRoom.roster.first { it.size == 1 }.single().identity.deviceId)

            joinerRoom.leave()
            hostRoom.leave()
        }
}
