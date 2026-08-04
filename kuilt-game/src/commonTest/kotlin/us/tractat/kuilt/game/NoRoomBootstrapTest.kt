@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The raw, no-[us.tractat.kuilt.session.Room] bootstraps return a plain [GameSession] with **no**
 * presence surface — the compile-time guarantee of #1618's Track 1 (§3, DES-1). `presence` is
 * reachable only through the [RoomGameSession] subtype, so a `gameNode` session has no way to hand
 * back a silently-empty presence flow.
 */
class NoRoomBootstrapTest {

    @Test
    fun `gameNode returns a plain GameSession with no presence surface`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("solo"))
            val self = NodeId(seam.selfId.value)

            val session: GameSession = backgroundScope.gameNode(
                seam,
                voterIds = setOf(self), // single voter ⇒ this node is always leader
                raftConfig = fastRaftConfig(seed = 1L),
            )

            // Compile- and run-time proof: the raw bootstrap is not a RoomGameSession, so
            // `presence` (and `roster`) simply do not exist on it — no empty flow to misread.
            assertNull(session as? RoomGameSession)

            session.close()
        }
}
