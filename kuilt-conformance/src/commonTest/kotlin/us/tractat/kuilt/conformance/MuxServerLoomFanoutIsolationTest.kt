package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Verifies the reference [MuxServerLoom] satisfies the shared
 * [RoomFanoutIsolationConformanceSuite].
 *
 * Keeping this in `:kuilt-conformance` (rather than `:kuilt-core`) lets `:kuilt-core` stay free of
 * a test dependency on `:kuilt-conformance`, and exercises the suite from a consumer — the same
 * pattern as [InMemoryLoomConformanceTest]. It replaces the former in-core
 * `RoomHubSeamIsolationTest`: the both-ends isolation gate now lives once, as this reusable suite.
 *
 * The harness wires a loopback fabric: an [InMemoryConnectionSource] the [MuxServerLoom] accepts
 * from, and clients created by [meshSeam] over a [connectionPair].
 */
class MuxServerLoomFanoutIsolationTest : RoomFanoutIsolationConformanceSuite() {

    override fun newHarness(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        authorizer: RoomAuthorizer,
        random: Random,
    ): FanoutHarness {
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = scope,
            selfId = PeerId("server"),
            authorizer = authorizer,
            dispatcher = dispatcher,
            random = random,
        )
        return FanoutHarness(serverLoom) { peerId, rng ->
            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            meshSeam(peerId, listOf(clientConn), dispatcher, rng)
        }
    }
}
