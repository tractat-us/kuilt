package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.conformance.SingleRoomHostConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import kotlin.time.Instant

/**
 * [NwRoomHost] satisfies [SingleRoomHostConformanceSuite].
 *
 * Over an [InMemoryLoom] rather than Network.framework: the host delegates its room's lifecycle
 * whole to `LoomRoomHost`, so what this pins is that the delegation keeps every obligation.
 */
class NwRoomHostConformanceTest : SingleRoomHostConformanceSuite() {
    override fun newHarness(scope: CoroutineScope): HostHarness {
        val loom = InMemoryLoom()
        val clients = SeamRoomFactory(loom = loom, scope = scope, clock = { Instant.fromEpochMilliseconds(0) })
        return HostHarness(
            host = NwRoomHost(loom, Pattern("room-host")),
            connect = { clients.adopt(loom.join(InMemoryTag("room-host")), SessionRole.Joiner) },
        )
    }
}
