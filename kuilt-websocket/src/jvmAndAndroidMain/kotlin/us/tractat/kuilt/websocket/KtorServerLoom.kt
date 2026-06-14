package us.tractat.kuilt.websocket

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Server-side [Loom] backed by Ktor WebSockets.
 *
 * Mounts a WebSocket route at [path] on the given [Application]. Each
 * incoming connection is wrapped directly in a [WebSocketSeam] — no
 * intermediate contract [Session] adapter. The raw
 * [io.ktor.server.websocket.DefaultWebSocketServerSession] is passed as the
 * common [io.ktor.websocket.DefaultWebSocketSession] supertype.
 *
 * **PeerId discovery:**
 *  - Server's [PeerId] is fixed at construction (defaults to a per-factory UUID).
 *  - Client's [PeerId] is read from the `?peer=<uuid>` query parameter set by
 *    [KtorClientLoom.join]. Connections without a `peer=` parameter are assigned
 *    an `anon-<uuid>` placeholder.
 *
 * @param dispatcher Scheduler for each per-connection seam's read/write loops; the loom
 *   confines it to a single thread via `limitedParallelism(1)`. Production default is
 *   [Dispatchers.IO]; tests inject [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 */
@OptIn(ExperimentalUuidApi::class)
public class KtorServerLoom(
    application: Application,
    private val path: String,
    public val selfPeerId: PeerId = PeerId("server-${Uuid.random()}"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Loom {
    private val connectionChannel = Channel<Seam>(capacity = Channel.UNLIMITED)

    init {
        installWebSocketsIfAbsent(application)
        application.routing {
            webSocket(path) {
                val clientPeerValue = call.request.queryParameters[PEER_QUERY_PARAM]
                val clientPeerId =
                    clientPeerValue
                        ?.let { PeerId(it) }
                        ?: PeerId("anon-${Uuid.random()}")
                val seam =
                    WebSocketSeam(
                        selfId = selfPeerId,
                        remoteId = clientPeerId,
                        session = this,
                        dispatcher = dispatcher.limitedParallelism(1),
                    )
                connectionChannel.send(seam)
                // Keep the WebSocket handler alive while the seam has the remote peer.
                seam.peers.first { clientPeerId !in it }
            }
        }
    }

    /**
     * Suspends until the next incoming WebSocket connection is established.
     * Returns a [Seam] for that connection.
     */
    public suspend fun nextLink(): Seam = connectionChannel.receive()

    /**
     * Establishes a [Seam]:
     * - [Rendezvous.New] — equivalent to [nextLink]; the [Pattern] is accepted for
     *   [Loom] compatibility but is not used.
     * - [Rendezvous.Existing] — not supported on the server loom; throws [UnsupportedOperationException].
     */
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New -> nextLink()
            is Rendezvous.Existing ->
                throw UnsupportedOperationException(
                    "KtorServerLoom does not join advertisements. " +
                        "Use KtorClientLoom.join(WebSocketAdvertisement) for the client side.",
                )
        }

    private fun installWebSocketsIfAbsent(application: Application) {
        if (application.pluginOrNull(WebSockets) == null) {
            application.install(WebSockets)
        }
    }

}
