package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.peerMesh
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Client-side [Loom] that joins a **hub-spoke mesh** — the spoke counterpart of a
 * [us.tractat.kuilt.core.MuxServerLoom] hub.
 *
 * [join] opens a WebSocket to the [WebSocketAdvertisement] URL and returns a mesh spoke
 * [Seam]: it exchanges the in-band mesh preamble
 * ([us.tractat.kuilt.core.fabric.MeshSeam]'s `MeshHello`) so both ends learn each other's
 * [PeerId] over the wire. This is exactly what a `MuxServerLoom` hub requires — it
 * handshakes every accepted connection as a mesh spoke — and it is why this loom exists:
 * pointing a [KtorClientLoom] at a `MuxServerLoom` hub silently never completes admit
 * (a [KtorClientLoom] returns a 2-peer [WebSocketSeam] with **no** in-band handshake, so
 * the hub's preamble is never satisfied). Before this loom, every consumer hand-rolled the
 * `meshSeam(selfId, listOf(WebSocketConnection(session)), dispatcher)` base itself.
 *
 * **Pairing:** `KtorMeshClientLoom` ↔ [us.tractat.kuilt.core.MuxServerLoom] hub — hub-spoke
 * mesh with a `MeshHello` preamble. Contrast [KtorClientLoom] ↔ [KtorServerLoom]/[KtorRoomHost] —
 * a 2-peer relay with no in-band handshake.
 *
 * **PeerId discovery:**
 *  - This spoke's own [PeerId] is fixed at construction as [selfPeerId] and appended as
 *    `?peer=<id>` on every join (mirroring [KtorClientLoom]) and, redundantly, carried in the
 *    in-band `MeshHello`.
 *  - The remote hub's [PeerId] is learned from the `MeshHello` preamble it sends back — it does
 *    **not** come from [WebSocketAdvertisement.serverPeerId] (the mesh handshake is authoritative).
 *
 * **Stable identity across reconnects:** supplying [selfPeerId] gives this loom a fixed fabric
 * identity reused on every call to [weave]/[join] — required wherever a server derives a stable
 * learner identity from the admitted [PeerId]. The default mints a fresh random identity per loom
 * instance.
 *
 * **HttpClient lifecycle:** the [httpClient] is not closed by this loom. Callers are responsible
 * for closing it when all connections are done.
 *
 * @param dispatcher Scheduler for the mesh spoke's per-link read loop; passed **directly** to
 *   [peerMesh] (which guards its own state with primitives — the dispatcher is scheduling-only,
 *   never a serialization crutch). Production default is [Dispatchers.Default]; tests inject a
 *   dispatcher derived from the test scheduler.
 * @param selfPeerId The fabric identity this loom presents on every join. Defaults to a random
 *   UUID minted once at construction; supply a deterministic value for stable identity across
 *   reconnects.
 * @param random Source of the per-connection mesh nonce. Production defaults to [Random.Default];
 *   tests pass a seeded [Random] so the dedup tiebreak is deterministic.
 * @param policy Delivery policy for the spoke seam's inbound spool. Defaults to
 *   [DeliveryPolicy.Reliable].
 *
 * @see KtorClientLoom the 2-peer relay client (pairs with [KtorServerLoom]/[KtorRoomHost]).
 * @see us.tractat.kuilt.core.MuxServerLoom the hub this spoke joins.
 * @see peerMesh the composition this loom produces — a peer-mesh spoke that latches Torn when the
 *   hub drops its single link (honouring the incoming-completes-on-Torn contract).
 */
@OptIn(ExperimentalUuidApi::class)
public class KtorMeshClientLoom(
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val selfPeerId: PeerId = PeerId(Uuid.random().toString()),
    private val random: Random = Random.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Loom {
    override fun capability(): TransportCapability =
        TransportCapability(
            roles = setOf(TransportRole.ServerRelay, TransportRole.Data),
            availability = FabricAvailability.Available,
        )

    /**
     * Establishes a mesh spoke [Seam]:
     * - [Rendezvous.New] — not meaningful for a client; throws [UnsupportedOperationException].
     * - [Rendezvous.Existing] — connects to the [WebSocketAdvertisement] URL and returns a mesh
     *   spoke [Seam] over one [WebSocketConnection], completing the `MeshHello` preamble.
     *
     * @throws UnsupportedOperationException for [Rendezvous.New].
     * @throws IllegalArgumentException if the tag is not a [WebSocketAdvertisement].
     */
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New ->
                throw UnsupportedOperationException(
                    "KtorMeshClientLoom does not open sessions. " +
                        "Use join(WebSocketAdvertisement) to connect to a MuxServerLoom hub.",
                )
            is Rendezvous.Existing -> {
                val advertisement = rendezvous.tag
                require(advertisement is WebSocketAdvertisement) {
                    "KtorMeshClientLoom only joins WebSocketAdvertisement, got ${advertisement::class}"
                }
                val urlWithPeer = appendPeerQuery(advertisement.url, selfPeerId)
                val wsSession = httpClient.webSocketSession(urlWithPeer)
                peerMesh(
                    selfId = selfPeerId,
                    connections = listOf(WebSocketConnection(wsSession)),
                    dispatcher = dispatcher,
                    random = random,
                    policy = policy,
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
