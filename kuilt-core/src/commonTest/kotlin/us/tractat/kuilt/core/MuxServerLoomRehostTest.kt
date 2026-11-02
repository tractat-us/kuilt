@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * How [MuxServerLoom]'s room table treats a closed room (#3041): a closed hub leaves it, so the next
 * `host` of its name gets a fresh hub, and the hub leaves **by identity**, so a predecessor's repeat
 * close never evicts the successor hosted under the same name.
 *
 * That a re-hosted room actually admits members is the shared obligation
 * `RoomFanoutIsolationConformanceSuite.aRoomHostedAgainUnderAClosedRoomsNameAdmitsMembers`. This
 * test pins the table bookkeeping beneath it, which no admission property reaches: a fix that
 * removed by name would pass that property and still let a stale `close()` kill a live room.
 */
class MuxServerLoomRehostTest {

    @Test
    fun `a predecessor's repeat close does not evict the room re-hosted under its name`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val loom = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(3041L)).serverLoom

            val first = loom.host(Pattern("t"))
            first.close()
            val second = loom.host(Pattern("t"))
            // A stale handle closed again — the shape of a consumer's double leave, or a teardown
            // racing a reaper that already closed it.
            first.close()
            val third = loom.host(Pattern("t"))

            assertAll(
                { assertNotSame(first, second, "a host after close must open a fresh room, not hand back the closed one") },
                { assertSame(second, third, "the predecessor's repeat close must not evict the live room under its name") },
                { assertIs<SeamState.Woven>(third.state.value, "the room under the name must still be live") },
            )
        }
}
