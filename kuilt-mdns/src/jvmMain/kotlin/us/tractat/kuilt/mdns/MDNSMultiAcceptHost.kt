package us.tractat.kuilt.mdns

import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.websocket.KtorServerLoom
import java.util.UUID
import javax.jmdns.JmDNS

/**
 * Advertises **one** mDNS service and accepts **many** joiners under it.
 *
 * One host publishes a single Bonjour / mDNS advertisement; every nearby peer
 * that discovers it connects to the same WebSocket server, and this host hands
 * back one accepted [Seam] per joiner. The advertisement is registered once and
 * stays up while joiners come and go — publishing is decoupled from accepting.
 *
 * ## Why this exists — one advertisement, many accepted seams
 *
 * [MDNSPeerLinkFactory] fuses registration and acceptance: its
 * [Rendezvous.New][us.tractat.kuilt.core.Rendezvous.New] path re-registers the
 * mDNS service on **every** accept, because a single [weave][us.tractat.kuilt.core.Loom.weave]
 * call both advertises and blocks for one connection. That is the right shape for
 * the degenerate two-peer case, but a host that admits N joiners under one
 * advertisement would re-publish the service N times.
 *
 * This helper separates the two primitives it composes:
 *  - [KtorServerLoom] mounts the WebSocket route once at construction and buffers
 *    every incoming connection, so [nextSeam] can be called repeatedly — accept-N.
 *  - [MDNSServiceAdvertiser] is the advertise-once primitive: [advertise] registers
 *    the service a single time; [close] unregisters it.
 *
 * ## Usage
 *
 * ```kotlin
 * val host = MDNSMultiAcceptHost(
 *     serviceType = MDNSServiceType("_myapp._tcp"),
 *     application = ktorApplication,   // already listening
 *     jmdns = jmdns,
 *     port = 8080,
 *     pattern = Pattern("My Session"),
 * )
 * host.advertise()                     // register the mDNS service exactly once
 * host.seams().collect { seam -> handle(seam) }   // one Seam per joiner
 * // …later…
 * host.close()                         // unregister the service
 * ```
 *
 * ## Lifecycle — this helper owns neither the fabric nor the discovery instance
 *
 * The caller owns:
 *  - the [JmDNS] instance ([jmdns]) — [close] only unregisters this host's service;
 *    it never closes [jmdns], which may back other advertisements or discoverers.
 *  - the Ktor [Application] / server — this helper mounts a route on it but never
 *    starts or stops it.
 *
 * [advertise] must be called **after** the server is listening, so the advertised
 * port actually accepts connections. It runs the blocking JmDNS registration on
 * [Dispatchers.IO].
 *
 * @param serviceType The mDNS service type. Supply the canonical base form
 *   (e.g. `MDNSServiceType("_myapp._tcp")`) — the JmDNS `.local.` suffix is appended
 *   internally by the advertiser.
 * @param application Ktor [Application] to mount the WebSocket accept route on.
 * @param jmdns The [JmDNS] instance to register on. Shared and **not** closed here.
 * @param port TCP port the local WebSocket server listens on — advertised in the TXT record.
 * @param pattern Session descriptor; its [Pattern.sessionName] is the advertised service name.
 * @param wsPath WebSocket path to mount and advertise (default: [MDNSAdvertisement.DEFAULT_WS_PATH]).
 * @param selfPeerId This host's [PeerId], embedded in the advertisement and presented to
 *   every joiner. Defaults to a fresh per-host UUID.
 * @param serverDispatcher Scheduler for each accepted seam's read/write loops (default: [Dispatchers.IO]).
 * @param hostOs OS family of this host — written as [MDNSAdvertisement.TXT_KEY_HOST_OS].
 * @param fabrics Comma-separated transport labels this host accepts (e.g. `"ws"`).
 * @param txtExtensions Arbitrary application-supplied TXT record key–value pairs, written
 *   alongside the kuilt-reserved fields and recovered by [MDNSServiceDiscoverer].
 *
 * @see MDNSPeerLinkFactory the single-accept sibling: one advertisement, one accepted seam per [weave][us.tractat.kuilt.core.Loom.weave].
 */
public class MDNSMultiAcceptHost(
    serviceType: MDNSServiceType,
    application: Application,
    private val jmdns: JmDNS,
    private val port: Int,
    pattern: Pattern,
    wsPath: String = MDNSAdvertisement.DEFAULT_WS_PATH,
    selfPeerId: PeerId = PeerId("mdns-${UUID.randomUUID()}"),
    serverDispatcher: CoroutineDispatcher = Dispatchers.IO,
    hostOs: MDNSAdvertisement.HostOs? = null,
    fabrics: String? = null,
    txtExtensions: Map<String, String> = emptyMap(),
) {
    // Build the server FIRST — this mounts the WS route and starts buffering
    // incoming connections in the loom's UNLIMITED channel before we advertise.
    private val server =
        KtorServerLoom(
            application = application,
            path = wsPath,
            selfPeerId = selfPeerId,
            dispatcher = serverDispatcher,
        )

    // A single advertiser — advertise once, accept many.
    private val advertiser =
        MDNSServiceAdvertiser(
            serviceType = serviceType,
            jmdns = jmdns,
            displayName = pattern.sessionName,
            port = port,
            selfId = server.selfPeerId,
            wsPath = wsPath,
            hostOs = hostOs,
            fabrics = fabrics,
            // Source the room identity from the host Pattern so the room-bound
            // admission guard is default-on for any host that declared a room.
            // Null (the Pattern default) advertises no room and stays permissive.
            roomKey = pattern.roomKey,
            txtExtensions = txtExtensions,
        )

    /** This host's [PeerId] — presented to every joiner and embedded in the advertisement. */
    public val selfPeerId: PeerId = server.selfPeerId

    /**
     * Registers the mDNS service. Call exactly once, **after** the server is
     * listening. The blocking JmDNS registration runs on [Dispatchers.IO].
     */
    public suspend fun advertise(): Unit = withContext(Dispatchers.IO) { advertiser.register() }

    /**
     * Suspends until the next joiner connects and returns its accepted [Seam].
     * Call repeatedly — one [Seam] per joiner. The mDNS service is **not**
     * re-registered on each accept.
     *
     * **Self-discovery guard (#1489):** JmDNS / NWBrowser return a device's own
     * advertisement to its own browser, so a symmetric advertise+browse peer can
     * dial its own host (the #1466 recipe, one transport up). Such a self-connection
     * resolves the accepted seam's only remote to [selfPeerId], collapsing its
     * `peers` to `{selfPeerId}`. This method silently drops (closes) any such
     * self-connection and keeps waiting for a genuine joiner — self is never surfaced.
     */
    public suspend fun nextSeam(): Seam = acceptNonSelfSeam { server.nextLink() }

    /**
     * A cold stream of accepted seams — repeatedly calls [nextSeam], emitting one
     * [Seam] per joiner. Convenience over an explicit accept-loop.
     */
    public fun seams(): Flow<Seam> = flow { while (true) emit(nextSeam()) }

    /**
     * Unregisters the mDNS service. Does **not** close [jmdns] or stop the server —
     * the caller owns both.
     */
    public fun close(): Unit = advertiser.unregister()
}

/**
 * The self-dial-drop accept loop behind [MDNSMultiAcceptHost.nextSeam]: pull links from [nextLink]
 * until one carries a remote distinct from `selfId`, closing (and skipping) every self-dial on the way.
 *
 * Split out from [MDNSMultiAcceptHost] so the drop path is drivable without a socket — the loop's
 * only interesting behaviour is what it does when a dropped seam refuses to close, and a real
 * `KtorServerLoom` seam cannot be made to refuse.
 *
 * **The dropped seam's failure to close must not cancel the accept that follows** (#1834/#2286). Not
 * `runCatchingCancellable`: that helper discriminates on TYPE, and type cannot tell "my job was
 * cancelled" from "the seam minted one" — most often a `close` wrapped in `withTimeout`, which throws
 * `TimeoutCancellationException` *to its caller* without cancelling that caller. Re-throwing it here
 * escapes `nextSeam()` **and** the `seams()` flow, and because the escaping throwable is a
 * `CancellationException` the host is *cancelled rather than failed* — no handler, no stack trace, and
 * it silently stops accepting joiners for the rest of its life. There is no shield here, so our own
 * cancellation is real and must still propagate: [ensureActive] is exactly that discriminator. See
 * `MeshSeam.closeBestEffort` for the full rationale — the trigger is not "a loop", it is whether
 * anything we still owe follows the close.
 */
internal suspend fun acceptNonSelfSeam(nextLink: suspend () -> Seam): Seam {
    while (true) {
        val seam = nextLink()
        if ((seam.peers.value - seam.selfId).isEmpty()) {
            // Remote resolved to self (or presented no distinct identity): a self-dial. Drop it.
            try {
                seam.close(CloseReason.Normal)
            } catch (_: Throwable) {
                // Genuinely our own cancellation → re-throw. Anything else — including a
                // CancellationException the seam minted itself — is this seam's failure alone;
                // the next accept still happens.
                currentCoroutineContext().ensureActive()
            }
            continue
        }
        return seam
    }
}
