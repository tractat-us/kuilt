package us.tractat.kuilt.websocket

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.channels.Channel
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource

/**
 * Server-side [ConnectionSource] backed by Ktor WebSockets — the Connection-aggregation (hub)
 * counterpart of [KtorServerLoom] (which is the 2-peer/relay topology). Mounts a WebSocket route
 * at [path]; each accepted session is wrapped in the internal [WebSocketConnection] and emitted as
 * a raw [Connection] (a hub spoke), so a composer (`hostedOverlay`) can bond many into one hub.
 *
 * A WS session is *either* a relay seam ([KtorServerLoom]) *or* a hub spoke (this) — decided by
 * which accept object the server installs on the route.
 */
public class KtorConnectionSource(
    application: Application,
    path: String,
) : ConnectionSource {
    private val connections = Channel<Connection>(capacity = Channel.UNLIMITED)

    init {
        if (application.pluginOrNull(WebSockets) == null) application.install(WebSockets)
        application.routing {
            webSocket(path) {
                connections.send(WebSocketConnection(this))
                // Hold the handler open for the connection's lifetime so Ktor does not close the
                // session out from under the consuming Mesh.  The consuming Mesh owns `incoming` —
                // do NOT read session.incoming here (single-collection contract).
                closeReason.await()
            }
        }
    }

    override suspend fun accept(): Connection = connections.receive()
}
