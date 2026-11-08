package us.tractat.kuilt.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import us.tractat.kuilt.conformance.PerConnectionRoomHostConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import kotlin.time.Instant

/**
 * [KtorRoomHost] satisfies [PerConnectionRoomHostConformanceSuite].
 *
 * Through its loom-taking constructor over [PerConnectionLoom], in virtual time, rather than over a
 * WebSocket. What these cases pin is the accept loop's handling of each room and handler, which is
 * the same code whichever loom feeds it. Over Ktor's test engine the same cases would also need a
 * client that never redials after its host leaves, and a real-time clock for every wait.
 */
class KtorRoomHostConformanceTest : PerConnectionRoomHostConformanceSuite() {
    override fun newHarness(scope: CoroutineScope): HostHarness {
        val loom = PerConnectionLoom()
        val clients = SeamRoomFactory(loom = loom, scope = scope, clock = { Instant.fromEpochMilliseconds(0) })
        return HostHarness(
            host = KtorRoomHost(path = "/ws/room-host-conformance", pattern = Pattern(SESSION), loom = loom),
            connect = { clients.adopt(loom.connect(), SessionRole.Joiner) },
        )
    }

    /**
     * A [Loom] that, like `KtorServerLoom`, hands its host a fresh two-peer seam for every accepted
     * connection: [connect] opens one, on its own [InMemoryLoom], and returns the client's end.
     *
     * Each connection gets its own mesh because one [InMemoryLoom] is one flat room, so two
     * connections on it would join each other. Hosting is the only weave it answers; a client end
     * comes from [connect], so nothing can redial through it.
     */
    private class PerConnectionLoom : Loom {
        private val accepted = Channel<Seam>(Channel.UNLIMITED)

        override suspend fun weave(rendezvous: Rendezvous): Seam = when (rendezvous) {
            is Rendezvous.New -> accepted.receive()
            is Rendezvous.Existing -> error("a client end comes from connect(), so a redial has nowhere to go")
        }

        suspend fun connect(): Seam {
            val mesh = InMemoryLoom()
            accepted.send(mesh.host(Pattern(SESSION)))
            return mesh.join(InMemoryTag(SESSION))
        }
    }

    private companion object {
        const val SESSION = "room-host"
    }
}
