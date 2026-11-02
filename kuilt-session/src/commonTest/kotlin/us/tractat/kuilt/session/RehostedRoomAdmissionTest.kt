package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A [SeamRoomFactory] room hosted over a `MuxServerLoom`, left, and hosted again under the same name
 * admits a joiner (#3041).
 *
 * This is the consumer's path end to end: a lobby server that reaps an idle table with
 * [Room.leave] and re-hosts it on the next join by code, over the one server loom it built at
 * startup. `SeamRoom.leave` closes the hub it was given, so the second `host` reaches the loom with a
 * name whose room is closed. The loom-level obligation is
 * `RoomFanoutIsolationConformanceSuite.aRoomHostedAgainUnderAClosedRoomsNameAdmitsMembers`; this pins
 * that a Room built on top of that loom inherits it.
 *
 * Both waits are bounded in virtual time and fail naming the roster they saw, so the defect reds as
 * a diagnosis rather than a wedge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RehostedRoomAdmissionTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    @Test
    fun `a room hosted again under a left room's name admits a joiner`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(3041L))
            val hostFactory = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock = zeroClock)

            hostFactory.host(Pattern("t")).leave()
            val rehosted = hostFactory.host(Pattern("t"))

            val joinerFactory =
                SeamRoomFactory(fabric.clientLoom(PeerId("joiner"), Random(1L)), backgroundScope, clock = zeroClock)
            val joiner = joinerFactory.join(InMemoryTag("t"))

            val joinerRoster = withTimeoutOrNull(5.seconds) { joiner.roster.first { it.isNotEmpty() } }
            val hostRoster = withTimeoutOrNull(5.seconds) { rehosted.roster.first { it.isNotEmpty() } }

            assertAll(
                {
                    assertNotNull(
                        joinerRoster,
                        "the joiner must be admitted to the re-hosted room; roster ${joiner.roster.value}",
                    )
                },
                {
                    assertEquals(
                        setOf(joiner.selfId),
                        rehosted.roster.value.map { it.id }.toSet(),
                        "the re-hosted room must admit the joiner; saw $hostRoster",
                    )
                },
            )

            joiner.leave()
            rehosted.leave()
        }
}
