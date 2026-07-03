package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
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
 * The harness is the packaged [InMemoryRoomFabric] double: its [InMemoryRoomFabric.serverLoom] is
 * the [MuxServerLoom] under test, and each client is a raw multi-channel seam from
 * [InMemoryRoomFabric.clientSeam]. Dogfooding the double here proves it is a faithful packaging of
 * the manual `InMemoryConnectionSource` + `MuxServerLoom` + `meshSeam` wiring this test used before.
 */
class MuxServerLoomFanoutIsolationTest : RoomFanoutIsolationConformanceSuite() {

    override fun newHarness(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        authorizer: RoomAuthorizer,
        random: Random,
    ): FanoutHarness {
        val fabric = InMemoryRoomFabric(scope, dispatcher, authorizer, random)
        return FanoutHarness(fabric.serverLoom) { peerId, rng -> fabric.clientSeam(peerId, rng) }
    }
}
