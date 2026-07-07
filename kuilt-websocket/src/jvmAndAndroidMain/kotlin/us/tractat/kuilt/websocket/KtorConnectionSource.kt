package us.tractat.kuilt.websocket

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.channels.Channel
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.withPrincipal

/**
 * Server-side [ConnectionSource] backed by Ktor WebSockets — the Connection-aggregation (hub)
 * counterpart of [KtorServerLoom] (which is the 2-peer/relay topology). Mounts a WebSocket route
 * at [path]; each accepted session is wrapped in the internal [WebSocketConnection] and emitted as
 * a raw [Connection] (a hub spoke), so a composer (`hostedOverlay`) can bond many into one hub.
 *
 * A WS session is *either* a relay seam ([KtorServerLoom]) *or* a hub spoke (this) — decided by
 * which accept object the server installs on the route.
 *
 * **Pairing:** feed this source to a [us.tractat.kuilt.core.MuxServerLoom] hub, and have spokes
 * join it with [KtorMeshClientLoom] — both sides speak the in-band `MeshHello` preamble. A
 * [KtorClientLoom] is the wrong client here: it returns a 2-peer [WebSocketSeam] with no in-band
 * handshake, so admit against this hub never completes. ([KtorClientLoom] pairs with the relay
 * topology — [KtorServerLoom]/[KtorRoomHost] — instead.)
 *
 * @param principalExtractor Derives a host-verified [Principal] from the accepting
 *   [ApplicationCall] (e.g. `call.principal<MyAuth>()?.let { Principal(it.id) }`). Runs in the
 *   WebSocket accept handler — the only point on the hosted path with access to the call object,
 *   after Ktor auth plugins have run — and the result rides the emitted [Connection] (via
 *   [withPrincipal]), landing on the hub's per-peer roster at mesh admission. No out-of-band
 *   `peer → principal` map. Defaults to no attestation.
 *
 *   **Extraction alone enforces nothing.** An extractor without a matching
 *   [us.tractat.kuilt.core.fabric.LinkAdmission] on the consuming mesh/overlay collects
 *   attestations but admits every connection anyway — pair it with an admission policy
 *   (`hostedOverlay(admission = ...)` / `gameHosted(admission = ...)`) to actually gate entry.
 */
public class KtorConnectionSource(
    application: Application,
    path: String,
    private val principalExtractor: (ApplicationCall) -> Principal? = { null },
) : ConnectionSource {
    private val connections = Channel<Connection>(capacity = Channel.UNLIMITED)

    init {
        if (application.pluginOrNull(WebSockets) == null) application.install(WebSockets)
        application.routing {
            webSocket(path) {
                connections.send(WebSocketConnection(this).withPrincipal(principalExtractor(call)))
                // Hold the handler open for the connection's lifetime so Ktor does not close the
                // session out from under the consuming Mesh.  The consuming Mesh owns `incoming` —
                // do NOT read session.incoming here (single-collection contract).
                closeReason.await()
            }
        }
    }

    override suspend fun accept(): Connection = connections.receive()
}
