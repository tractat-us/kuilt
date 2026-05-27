package us.tractat.kuilt.mdns

import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorServerLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.util.UUID
import javax.jmdns.JmDNS

/**
 * [Loom] that combines Bonjour / mDNS discovery with WebSocket
 * transport.
 *
 * **Separation of concerns:** this module is responsible only for *discovery*
 * (advertising the local peer, discovering remote peers). Actual bytes flow
 * over a WebSocket [Seam] provided by `:kuilt-websocket`.
 *
 * **[open]** — hosts a new session:
 *  1. Starts a Ktor WebSocket server on a free port (using [application]).
 *  2. Registers an mDNS service so nearby peers can find it.
 *  3. Returns the server's [Seam] (via [KtorServerLoom]).
 *
 * **[join]** — joins an existing session from a discovered [MDNSAdvertisement]:
 *  1. Converts the [MDNSAdvertisement] to a [WebSocketAdvertisement].
 *  2. Delegates to [KtorClientLoom] — exactly one TCP + WebSocket
 *     connection, no mDNS state on the joiner side.
 *
 * **Lifecycle:** the [jmdns] instance is shared and not closed by this factory.
 * Callers are responsible for closing it when all sessions are done.
 *
 * @param application Ktor [Application] to mount the WebSocket server route on
 *   (only needed for the [open] path).
 * @param jmdns JmDNS instance used for service registration/discovery.
 * @param port Port for the local WebSocket server (default: a random free port
 *   is chosen — pass an explicit value for predictable tests).
 * @param wsPath WebSocket path to register and advertise.
 * @param httpClientFactory Factory for producing the Ktor HttpClient used on
 *   the joiner side. Callers supply this so they control the client lifecycle.
 */
public class MDNSPeerLinkFactory(
    private val application: Application,
    private val jmdns: JmDNS,
    private val port: Int,
    private val wsPath: String = MDNSAdvertisement.DEFAULT_WS_PATH,
    private val httpClientFactory: () -> io.ktor.client.HttpClient,
) : Loom {
    private val serverFactory =
        KtorServerLoom(
            application = application,
            path = wsPath,
            selfPeerId = PeerId("mdns-${UUID.randomUUID()}"),
        )

    private var advertiser: MDNSServiceAdvertiser? = null

    /**
     * Opens a new session: registers the local peer via mDNS, then suspends
     * until the first remote peer connects via WebSocket.
     *
     * The mDNS service is registered under [config.displayName].
     */
    override suspend fun open(config: Pattern): Seam {
        registerMDNS(config.displayName)
        return serverFactory.open(config)
    }

    /**
     * Joins an existing session described by [advertisement].
     *
     * @throws IllegalArgumentException if [advertisement] is not an [MDNSAdvertisement].
     */
    override suspend fun join(advertisement: Tag): Seam {
        require(advertisement is MDNSAdvertisement) {
            "MDNSPeerLinkFactory only joins MDNSAdvertisement, got ${advertisement::class}"
        }
        val wsAdvertisement =
            WebSocketAdvertisement(
                url = advertisement.wsUrl,
                serverPeerId = advertisement.serverPeerId,
                displayName = advertisement.displayName,
            )
        val clientLoom = KtorClientLoom(httpClientFactory())
        return clientLoom.join(wsAdvertisement)
    }

    /**
     * The advertiser for the locally hosted session. `null` until [open] is
     * called. Exposed for testing; not part of the [Loom] contract.
     */
    public fun advertiser(): MDNSServiceAdvertiser? = advertiser

    /**
     * The server peer's [PeerId]. Expose so callers can build [MDNSAdvertisement]s
     * for the local session without an extra lookup.
     */
    public val selfPeerId: PeerId get() = serverFactory.selfPeerId

    private suspend fun registerMDNS(displayName: String) =
        withContext(Dispatchers.IO) {
            val a =
                MDNSServiceAdvertiser(
                    jmdns = jmdns,
                    displayName = displayName,
                    port = port,
                    selfId = serverFactory.selfPeerId,
                    wsPath = wsPath,
                )
            a.register()
            advertiser = a
        }
}
