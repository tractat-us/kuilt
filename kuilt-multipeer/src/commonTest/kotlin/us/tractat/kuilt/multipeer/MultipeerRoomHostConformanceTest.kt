package us.tractat.kuilt.multipeer

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.conformance.SingleRoomHostConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import kotlin.time.Instant

/**
 * [MultipeerRoomHost] satisfies [SingleRoomHostConformanceSuite].
 *
 * Over an [InMemoryLoom] rather than Multipeer Connectivity: the host adds nothing of its own to the
 * room's lifecycle, which it delegates whole to `LoomRoomHost`, so what this pins is that the
 * delegation keeps every obligation. A host that one day stops delegating is caught here.
 */
class MultipeerRoomHostConformanceTest : SingleRoomHostConformanceSuite() {
    override fun newHarness(scope: CoroutineScope): HostHarness {
        val loom = InMemoryLoom()
        val clients = SeamRoomFactory(loom = loom, scope = scope, clock = { Instant.fromEpochMilliseconds(0) })
        return HostHarness(
            host = MultipeerRoomHost(loom, Pattern("room-host")),
            connect = { clients.adopt(loom.join(InMemoryTag("room-host")), SessionRole.Joiner) },
        )
    }
}
