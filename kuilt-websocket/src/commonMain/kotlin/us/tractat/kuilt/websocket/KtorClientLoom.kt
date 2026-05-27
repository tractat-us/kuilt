package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Client-side [Loom] backed by Ktor WebSockets.
 *
 * [join] connects directly to a [WebSocketAdvertisement] URL and returns a
 * 2-peer [WebSocketSeam] — no intermediate contract [Session] adapter.
 *
 * **PeerId discovery:**
 *  - Client's own [PeerId] is generated as a UUID at [join] time and
 *    appended as `?peer=<uuid>` so the server can read it.
 *  - Server's [PeerId] comes from [WebSocketAdvertisement.serverPeerId].
 *
 * **HttpClient lifecycle:** the [httpClient] is not closed by this loom.
 * Callers are responsible for closing it when all connections are done.
 */
public class KtorClientLoom(
    private val httpClient: HttpClient,
) : Loom {
    /**
     * Establishes a [Seam]:
     * - [Rendezvous.New] — not meaningful for a client; throws [UnsupportedOperationException].
     * - [Rendezvous.Existing] — connects to the [WebSocketAdvertisement] URL and returns a 2-peer [Seam].
     *
     * @throws UnsupportedOperationException for [Rendezvous.New].
     * @throws IllegalArgumentException if the tag is not a [WebSocketAdvertisement].
     */
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New ->
                throw UnsupportedOperationException(
                    "KtorClientLoom does not open sessions. " +
                        "Use join(WebSocketAdvertisement) to connect to an existing server.",
                )
            is Rendezvous.Existing -> {
                val advertisement = rendezvous.tag
                require(advertisement is WebSocketAdvertisement) {
                    "KtorClientLoom only joins WebSocketAdvertisement, got ${advertisement::class}"
                }
                val selfId = PeerId(Uuid.random().toString())
                val urlWithPeer = appendPeerQuery(advertisement.url, selfId)
                val wsSession = httpClient.webSocketSession(urlWithPeer)
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                WebSocketSeam(
                    selfId = selfId,
                    remoteId = advertisement.serverPeerId,
                    session = wsSession,
                    scope = scope,
                )
            }
        }

    private fun appendPeerQuery(
        url: String,
        peerId: PeerId,
    ): String {
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}${PEER_QUERY_PARAM}=${peerId.value}"
    }

    internal companion object {
        const val PEER_QUERY_PARAM: String = "peer"
    }
}
