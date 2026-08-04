package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * #1357 — [Room.attestedPrincipals] is the cross-facade roster analogue of [Member.principal]:
 * a thin delegation to `(seam as? PrincipalRoster)?.attestedPrincipals`, mirroring
 * `GameSession.attestedPrincipals`. Populated over a hub seam that carries attestations (the
 * mux-hub `RoomHubSeam` path); a constant empty map over a seam with no roster concept (a plain
 * relay/in-memory fabric).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomAttestedPrincipalsTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    @Test
    fun `mux-hub room exposes the attested-principals roster keyed by the admitted peer`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(42L))
            val principal = Principal("device-x")

            val hostFactory = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock = zeroClock)
            val hostRoom = hostFactory.host(Pattern("room"))

            val joinerLoom = fabric.clientLoom(PeerId("joiner"), Random(1L), principal = principal)
            val joinerFactory = SeamRoomFactory(joinerLoom, backgroundScope, clock = zeroClock)
            joinerFactory.join(InMemoryTag("room"))

            val admitted = hostRoom.roster.first { it.isNotEmpty() }.single()
            val roster = hostRoom.attestedPrincipals.first { it.isNotEmpty() }

            assertEquals(
                mapOf(admitted.id to principal),
                roster,
                "Room.attestedPrincipals must match the admitted member's verified principal",
            )
        }

    @Test
    fun `a non-roster room reports an empty attested-principals roster`(): TestResult = runTest {
        val mesh = InMemoryLoom()
        val hostRoom = SeamRoomFactory(mesh, backgroundScope, zeroClock).host(Pattern("server"))
        assertTrue(
            hostRoom.attestedPrincipals.value.isEmpty(),
            "a seam with no PrincipalRoster carries an empty attested-principals roster",
        )
    }
}
