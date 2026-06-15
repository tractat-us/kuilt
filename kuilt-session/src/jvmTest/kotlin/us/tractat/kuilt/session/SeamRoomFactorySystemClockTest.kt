package us.tractat.kuilt.session

import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Structural contract test for [SeamRoomFactory.systemClock]: verifies that the factory
 * produces a [us.tractat.kuilt.session.Room] with the expected role.
 *
 * Runs under [runBlocking] (not [kotlinx.coroutines.test.runTest]) so that
 * [kotlin.time.Clock.System.now()] — wired inside [SeamRoomFactory.systemClock] —
 * does not run under a virtual-time test scheduler. The test asserts only the structural
 * contract (role == Host); it does not drive or observe timing.
 */
class SeamRoomFactorySystemClockTest {

    @Test
    fun `systemClock factory produces a Host room`() = runBlocking {
        val loom = InMemoryLoom()
        val factory = SeamRoomFactory.systemClock(loom = loom, scope = this)
        val room = factory.host(Pattern("SystemClockTest"))
        assertEquals(SessionRole.Host, room.role.value)
        room.leave()
    }
}
