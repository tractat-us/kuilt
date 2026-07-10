package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * #1352 — the multiplexed-hub server path (`MuxServerLoom → RoomHubSeam → SeamRoom`) must carry a
 * connection's host-verified [Principal] onto the admitted [Member], just as the relay path
 * (`Seam.withPrincipal`, pinned by [MemberPrincipalTest]) and the hosted overlay already do.
 *
 * A joiner connects over the room-isolating [InMemoryRoomFabric] with a principal attested onto its
 * server-end connection (as a real fabric's accept handler would). The host admits it through a
 * `SeamRoomFactory` over the shared [InMemoryRoomFabric.serverLoom]; the admitted member must carry
 * the verified principal — not `null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuxHubPrincipalTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    @Test
    fun `mux-hub admit carries the connection's verified principal onto the admitted member`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(42L))
            val principal = Principal("device-x")

            // Host one room over the mux-hub server loom.
            val hostFactory = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock = zeroClock)
            val hostRoom = hostFactory.host(Pattern("room"))

            // A joiner whose server-end connection is attested with a verified principal.
            val joinerLoom = fabric.clientLoom(PeerId("joiner"), Random(1L), principal = principal)
            val joinerFactory = SeamRoomFactory(joinerLoom, backgroundScope, clock = zeroClock)
            joinerFactory.join(InMemoryTag("room"))

            // Await the admit handshake, then assert the verified principal rode onto the member.
            val admitted = hostRoom.roster.first { it.isNotEmpty() }.single()
            assertEquals(
                principal,
                admitted.principal,
                "mux-hub host must carry the verified principal onto the admitted member",
            )
        }
}
