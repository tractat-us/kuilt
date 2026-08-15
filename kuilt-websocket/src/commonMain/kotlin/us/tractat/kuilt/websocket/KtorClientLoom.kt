package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Weft
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Client-side [Loom] backed by Ktor WebSockets.
 *
 * [join] connects directly to a [WebSocketAdvertisement] URL and returns a
 * 2-peer [WebSocketSeam] — no intermediate contract [Session] adapter.
 *
 * **Pairing:** `KtorClientLoom` ↔ [KtorServerLoom]/[KtorRoomHost] — a 2-peer relay with **no**
 * in-band handshake. It is **not** the client for a [us.tractat.kuilt.core.MuxServerLoom] hub:
 * that hub handshakes every spoke with an in-band `MeshHello` preamble a [WebSocketSeam] never
 * sends, so pointing this loom at one silently never completes admit. Use [KtorMeshClientLoom]
 * (hub-spoke mesh) for a `MuxServerLoom` hub.
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
 * **Per-dial credentials:** [weft] is invoked fresh inside every [weave] call — the first dial
 * and every subsequent redial — so a caller can mint a single-use credential (e.g. a short-lived
 * WS ticket) that survives kuilt's transparent reconnect instead of being baked once into a
 * static [WebSocketAdvertisement.url]. See
 * [#1330](https://github.com/tractat-us/kuilt/issues/1330).
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
 * @param weft Supplies a [WebSocketDialContext] fresh on every dial. Defaults to an empty
 *   context (no extra query params/headers).
 * @param connectivity Live device-reachability observer driving every woven [Seam]'s
 *   [Seam.capability] (#1725). Defaults to [UnobservedConnectivity] — the identity element, under
 *   which a seam reports the honest [FabricAvailability.Unknown] floor exactly as before. Supply
 *   `androidConnectivityObserver(context)` on Android or `browserConnectivityObserver()` on wasmJs;
 *   the desktop JVM has no portable observer and is meant to be left unwired (see
 *   [ConnectivityObserver]).
 */
@OptIn(ExperimentalUuidApi::class)
public class KtorClientLoom(
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val selfPeerId: PeerId = PeerId(Uuid.random().toString()),
    private val weft: Weft<WebSocketDialContext> = { WebSocketDialContext() },
    private val connectivity: ConnectivityObserver = UnobservedConnectivity,
) : Loom {
    /**
     * The **pre-connect** report, deliberately unchanged by #1725: it answers "can this fabric be
     * attempted on this runtime" — a Ktor WebSocket client exists on every target — not "is the
     * device's path up right now", which is the live per-session [Seam.capability]'s job. Keeping
     * the two apart is the #1712 distinction; [connectivity] therefore feeds the seam, not this.
     */
    override fun capability(): TransportCapability =
        TransportCapability(
            roles = RELAY_ROLES,
            availability = FabricAvailability.Available,
        )

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
                val dialContext = weft()
                // PEER_QUERY_PARAM is set last so it always wins if dialContext.queryParams
                // happens to contain a "peer" key — the fabric identity contract (#544) is not
                // something a credential weft should be able to silently override.
                val queryParams = linkedMapOf<String, String>()
                queryParams.putAll(dialContext.queryParams)
                queryParams[PEER_QUERY_PARAM] = selfPeerId.value
                val urlWithPeer = appendQueryParams(advertisement.url, queryParams)
                val wsSession =
                    httpClient.webSocketSession(urlWithPeer) {
                        dialContext.headers.forEach { (key, value) -> header(key, value) }
                    }
                WebSocketSeam(
                    selfId = selfPeerId,
                    remoteId = advertisement.serverPeerId,
                    session = wsSession,
                    dispatcher = dispatcher.limitedParallelism(1),
                    roles = RELAY_ROLES,
                    connectivity = connectivity,
                )
            }
        }

    private fun appendQueryParams(
        url: String,
        params: Map<String, String>,
    ): String {
        if (params.isEmpty()) return url
        val separator = if ('?' in url) "&" else "?"
        val encoded =
            params.entries.joinToString("&") { (key, value) ->
                "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
            }
        return "$url$separator$encoded"
    }
}
