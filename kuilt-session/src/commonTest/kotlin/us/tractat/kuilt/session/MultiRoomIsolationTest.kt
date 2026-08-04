package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The #1172 scenario, proven **isolated** on the packaged [InMemoryRoomFabric] double.
 *
 * Two `SeamRoomFactory.host()` rooms are opened over the **same** [InMemoryRoomFabric.serverLoom],
 * and a joiner targets only room 1. Because the server routes admission by room name (structural
 * per-room fanout isolation), room 2's roster must stay empty and the joiner must see only room-1
 * members. On the flat `InMemoryLoom` — a single broadcast domain — the joiner would be
 * cross-admitted into room 2 as well; this test pins that the room-isolating double does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiRoomIsolationTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    @Test
    fun `two host rooms over one server loom stay isolated and a joiner reaches only its room`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(42L))

            // Two rooms hosted over the SAME server loom.
            val hostFactory = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock = zeroClock)
            val room1 = hostFactory.host(Pattern("room-1"))
            val room2 = hostFactory.host(Pattern("room-2"))

            // A joiner that targets ONLY room-1.
            val joinerLoom = fabric.clientLoom(PeerId("joiner"), Random(1L))
            val joinerFactory = SeamRoomFactory(joinerLoom, backgroundScope, clock = zeroClock)
            val joinerRoom = joinerFactory.join(InMemoryTag("room-1"))

            // Await the admit handshake into room-1 on both ends, deterministically.
            room1.roster.first { it.isNotEmpty() }
            joinerRoom.roster.first { it.isNotEmpty() }

            assertAll(
                {
                    assertTrue(
                        room1.roster.value.any { it.id == joinerRoom.selfId },
                        "room-1 host roster must contain the admitted joiner",
                    )
                },
                {
                    assertEquals(
                        emptySet(),
                        room2.roster.value,
                        "room-2 roster must stay EMPTY — the joiner targeted room-1 (no cross-admission)",
                    )
                },
                {
                    // The joiner sees only room-1's host — no member leaks in from room-2.
                    assertEquals(
                        setOf(PeerId("server")),
                        joinerRoom.roster.value.map { it.id }.toSet(),
                        "joiner must observe only room-1's host",
                    )
                },
            )

            // A broadcast confirmation: room-1 still delivers to the joiner after isolation is proven.
            room1.broadcast(byteArrayOf(1, 2, 3))
            val frame = withTimeout(1.seconds) { joinerRoom.incoming.first() }
            assertTrue(
                frame.payload.contentEquals(byteArrayOf(1, 2, 3)),
                "joiner must receive room-1's broadcast",
            )
        }
}
