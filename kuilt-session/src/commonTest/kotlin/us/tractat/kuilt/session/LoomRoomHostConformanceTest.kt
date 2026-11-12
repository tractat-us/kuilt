package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.conformance.SingleRoomHostConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import kotlin.time.Instant

/** [LoomRoomHost] over an [InMemoryLoom] satisfies [SingleRoomHostConformanceSuite]. */
class LoomRoomHostConformanceTest : SingleRoomHostConformanceSuite() {
    override fun newHarness(scope: CoroutineScope): HostHarness {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
        val clients = SeamRoomFactory(loom = loom, scope = scope, clock = clock)
        return HostHarness(
            host = LoomRoomHost(loom, Pattern("room-host"), clock = clock),
            connect = { clients.adopt(loom.join(InMemoryTag("room-host")), SessionRole.Joiner) },
        )
    }
}
