package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 *  - Client's own [PeerId] is fixed at construction as [selfPeerId] and
 *    appended as `?peer=<id>` on every join so the server can read it.
 *  - Server's [PeerId] comes from [WebSocketAdvertisement.serverPeerId].
 *
 * **Stable identity across reconnects:** supplying [selfPeerId] gives this loom
 * a fixed fabric identity reused on every call to [weave]/[join]. This is required
 * for cluster-client failover: the server derives a learner [NodeId] from the
 * admitted [PeerId]; if a reconnect mints a new random id the server admits a
 * different learner and Raft routing breaks. The default mints a fresh random
 * identity per loom instance (mirroring the old per-join behaviour for callers
 * that do not need stable identity). See [#544](https://github.com/tractat-us/kuilt/issues/544).
 *
 * **HttpClient lifecycle:** the [httpClient] is not closed by this loom.
 * Callers are responsible for closing it when all connections are done.
 *
 * @param dispatcher Scheduler for the per-connection seam's read/write loops; the loom
 *   confines it to a single thread via `limitedParallelism(1)`. Production default is
 *   [Dispatchers.Default]; tests inject [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 * @param selfPeerId The fabric identity this loom presents on every join. Defaults to a
 *   random UUID minted once at construction; supply a deterministic value for stable
 *   cluster-client identity across reconnects.
 */
@OptIn(ExperimentalUuidApi::class)
public class KtorClientLoom(
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val selfPeerId: PeerId = PeerId(Uuid.random().toString()),
) : Loom {
    /**
     * Establishes a [Seam]:
     * - [Rendezvous.New] — not meaningful for a client; throws [UnsupportedOperationException].
     * - [Rendezvous.Existing] — connects to the [WebSocketAdvertisement] URL and returns a 2-peer [Seam].
     *
     * @throws UnsupportedOperationException for [Rendezvous.New].
     * @throws IllegalArgumentException if the tag is not a [WebSocketAdvertisement].
     */
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
                val urlWithPeer = appendPeerQuery(advertisement.url, selfPeerId)
                val wsSession = httpClient.webSocketSession(urlWithPeer)
                WebSocketSeam(
                    selfId = selfPeerId,
                    remoteId = advertisement.serverPeerId,
                    session = wsSession,
                    dispatcher = dispatcher.limitedParallelism(1),
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

}
