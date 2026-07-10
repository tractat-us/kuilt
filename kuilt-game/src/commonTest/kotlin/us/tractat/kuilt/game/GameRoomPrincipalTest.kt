@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * #1352 — the game-per-room composition (`GameRoom` → `starOverlay(RoomHubSeam)` → `GameSession`)
 * must surface a joiner's host-verified [Principal] on `GameSession.attestedPrincipals`, just as the
 * hosted overlay already does. This path was holed for the same root cause as the plain-room
 * mux-hub path: `RoomHubSeam` was `: Seam` only, so `GossipSeam`'s roster delegation
 * (`(base as? PrincipalRoster)`) fell through to the empty map and the game layer saw no principals.
 * Once `RoomHubSeam` is a [us.tractat.kuilt.core.PrincipalRoster] the delegation lights up with zero
 * game-layer change — this test pins that it stays lit.
 *
 * A joiner connects over [InMemoryRoomFabric] with a principal attested onto its server-end
 * connection; the server-core [GameSession] must report that verified principal, keyed by the
 * joiner's [PeerId] — not an empty roster.
 */
class GameRoomPrincipalTest {

    @Test
    fun `game-per-room over the mux hub surfaces the verified principal on the session roster`() =
        runTest(StandardTestDispatcher(), timeout = 15.seconds) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor])
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(1))
            val core = setOf(NodeId("server"))
            val placement = ConsensusPlacement.serverCore(core)

            val server = backgroundScope.gameNodeRoom(
                fabric.serverLoom, "table-a", voterIds = core,
                raftConfig = fastRaftConfig(seed = 1L), random = Random(11), clock = inertTestClock,
                placement = placement,
            )

            val principal = Principal("device-x")
            val aliceLoom = fabric.clientLoom(PeerId("alice"), Random(21), principal = principal)
            backgroundScope.gameNodeRoom(
                aliceLoom, "table-a", voterIds = core,
                raftConfig = fastRaftConfig(seed = 3L), random = Random(13), clock = inertTestClock,
                placement = placement,
            )

            val roster = server.attestedPrincipals.first { it.isNotEmpty() }
            assertEquals(
                principal,
                roster[PeerId("alice")],
                "GameSession.attestedPrincipals over GameRoom (starOverlay(RoomHubSeam)) must carry alice's verified principal",
            )
        }
}
