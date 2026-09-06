package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.freshPeerId
import javax.jmdns.JmDNS

/**
 * Join the machines on one local network into a session they find each other on by name.
 *
 * Give it a service name to advertise under and the Ktor [Application] to mount the socket on.
 * The returned loom's [us.tractat.kuilt.core.Loom.host] registers a Bonjour/mDNS record so nearby
 * peers can discover this machine, then waits for the first of them to connect;
 * [us.tractat.kuilt.core.Loom.join] takes an [MDNSAdvertisement] someone discovered — from
 * [MDNSServiceDiscoverer] — and dials it. Bytes then flow over a WebSocket: this module does
 * discovery, `:kuilt-websocket` does transport.
 *
 * This builds the same loom [MDNSPeerLinkFactory]'s constructor does. What it adds is the argument
 * *order* every kuilt fabric factory shares (#1430): the fabric's own required arguments first,
 * then the universal knobs. [MDNSPeerLinkFactory] keeps its own older order and remains the
 * surface for dependency injection and tests.
 *
 * Two knobs the convention names are **deliberately absent**, because this fabric cannot honour
 * them, and an argument that is accepted and then ignored is worse than one that does not exist:
 *
 * - **`policy`** — the [us.tractat.kuilt.core.DeliveryPolicy] governing the woven seam's inbox.
 *   Nothing here could carry it: this fabric's seams are built by
 *   [us.tractat.kuilt.websocket.KtorServerLoom] and [us.tractat.kuilt.websocket.KtorClientLoom],
 *   neither of which accepts a policy, and the `WebSocketSeam` they share calls
 *   [us.tractat.kuilt.core.fabric.identified] without one — so every WebSocket-backed seam takes
 *   that function's `DeliveryPolicy.Reliable` default and there is no path an argument could
 *   travel. That is the same shape as `:kuilt-tcp`'s #2323 and the fix belongs at the same layer,
 *   in `:kuilt-websocket`, not here. This factory gains a `policy` parameter when that lands, and
 *   not before.
 *
 * - **`weaveTimeout`** — `weave` puts no ceiling on either path. Hosting parks on
 *   [us.tractat.kuilt.websocket.KtorServerLoom.nextLink] until a peer dials in, which is what a
 *   "wait for someone on the LAN to join" host is *for*, not an oversight; and on the joining path
 *   the only network call is `httpClient.webSocketSession(…)`, whose timeouts the caller already
 *   owns — they configure them on the [HttpClient] they hand to [httpClientFactory]. Adding the
 *   parameter would mean adding a `withTimeout` that does not exist today, which is new behaviour
 *   rather than a surfaced knob. Same reasoning, and the same conclusion, as `tcpLoomHost`.
 *
 * @param serviceType The mDNS service type to advertise and browse under. Supply the canonical
 *   base form (e.g. `MDNSServiceType("_myapp._tcp")`); platform-specific suffixes are applied
 *   internally.
 * @param application The Ktor [Application] the WebSocket route is mounted on. Needed on the
 *   hosting path; the joining path dials out and does not use it.
 * @param jmdns The JmDNS instance used for service registration. Shared and **not** closed by the
 *   returned loom — the caller owns its lifecycle.
 * @param port The port the local WebSocket server listens on, and the one written into the
 *   advertised record.
 * @param selfId This peer's identity, on both paths: advertised in the mDNS record when hosting,
 *   and presented as `?peer=` when joining. Defaults to a fresh random [PeerId], distinct on every
 *   call. Read it back off the returned loom as [MDNSPeerLinkFactory.selfPeerId].
 * @param dispatcher The canonical `dispatcher` slot — it *schedules* the woven seam's read/write
 *   coroutines and is never a mutual-exclusion mechanism; the seam is thread-safe on its own.
 *   Must be a real dispatcher: the transport is a live Ktor WebSocket, so a virtual
 *   `TestDispatcher` would never run the socket loops.
 * @param wsPath Fabric-specific: the WebSocket path registered on [application] and written into
 *   the advertised record. Both ends must agree on it.
 * @param httpClientFactory Fabric-specific: supplies the Ktor [HttpClient] each join dials with.
 *   The caller supplies it so the caller owns the client's lifecycle — the loom never closes it.
 */
public fun mdnsLoom(
    serviceType: MDNSServiceType,
    application: Application,
    jmdns: JmDNS,
    port: Int,
    selfId: PeerId = freshPeerId(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    wsPath: String = MDNSAdvertisement.DEFAULT_WS_PATH,
    httpClientFactory: () -> HttpClient,
): MDNSPeerLinkFactory = MDNSPeerLinkFactory(
    serviceType = serviceType,
    application = application,
    jmdns = jmdns,
    port = port,
    wsPath = wsPath,
    httpClientFactory = httpClientFactory,
    selfId = selfId,
    dispatcher = dispatcher,
)
