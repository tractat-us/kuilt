package us.tractat.kuilt.multipeer

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.MultipeerConnectivity.MCEncryptionRequired
import platform.MultipeerConnectivity.MCNearbyServiceAdvertiser
import platform.MultipeerConnectivity.MCNearbyServiceAdvertiserDelegateProtocol
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCNearbyServiceBrowserDelegateProtocol
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.darwin.NSObject
import us.tractat.kuilt.core.ActiveSeamSlot
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.multipeer.internal.MCSessionLink

private val log = KotlinLogging.logger("us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory")

/**
 * iOS-side `MultipeerPeerLinkFactory`. Owns the local `MCPeerID`, the active
 * `MCSession`/`MCNearbyServiceAdvertiser` for the host role, and the shared
 * `MCNearbyServiceBrowser` used by both discovery and join.
 *
 * Two-call lifecycle:
 *  - [open] — host. Creates a session, starts advertising, auto-accepts
 *    invitations.
 *  - [join] — client. Looks up the target `MCPeerID` in the shared peer map
 *    and sends an invitation **on the same browser instance that discovered
 *    it**. Apple's MC keeps each peer in the discovering browser's internal
 *    "peers dictionary"; a fresh `MCNearbyServiceBrowser` has never seen the
 *    peer and silently drops the invitation.
 *
 * The factory is single-session: a second call to [open] or [join] before
 * the previous link is closed throws. The lobby is expected to drive at most
 * one live session per device.
 *
 * Browsing is driven from `MultipeerServiceBrowser.discoveries` through
 * [startBrowsing] / [stopBrowsing]. [join] requires browsing to be active so
 * the discovering browser is still bound to the target peer; the lobby holds
 * the discoveries flow open across the join.
 *
 * Auto-accept invite policy: the host has already opted in by tapping
 * "Host", so we accept every inbound invitation. A confirmation prompt is
 * easy to layer on later by replacing [AcceptAllAdvertiserDelegate].
 */
@OptIn(ExperimentalForeignApi::class)
public actual class MultipeerPeerLinkFactory actual constructor(
    displayName: String,
    serviceType: String,
) : Loom {
    internal val displayName: String = displayName
    internal val serviceType: String = serviceType
    internal val localPeerId: MCPeerID = MCPeerID(displayName = displayName)

    /**
     * Stable handle → live `MCPeerID` for peers seen by the browser. The
     * advertisement carries the handle; [join] uses the map to recover the
     * actual ObjC object (whose lifetime is bound to the browse session).
     */
    internal val knownPeers: MutableMap<String, MCPeerID> = mutableMapOf()

    private val _visiblePeers: MutableStateFlow<Set<MultipeerAdvertisement>> = MutableStateFlow(emptySet())
    public actual val visiblePeers: StateFlow<Set<MultipeerAdvertisement>> = _visiblePeers.asStateFlow()

    /**
     * Fires the handle of every peer the apple-side `BrowserDelegate` sees in
     * a `browser(_:lostPeer:)` callback. iOS lobbies don't read this directly
     * — they collect [visiblePeers] — but the macOS K/N → JNA bridge in
     * `BridgeBrowser.kt` subscribes to it to forward `lostPeer` across JNA.
     *
     * Buffered + `DROP_OLDEST` so an inactive bridge collector can never apply
     * back-pressure to the Cocoa runloop the delegate fires on.
     */
    internal val lostPeerHandles: MutableSharedFlow<String> =
        MutableSharedFlow(
            extraBufferCapacity = LOST_PEER_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var advertiser: MCNearbyServiceAdvertiser? = null
    private var advertiserDelegate: MCNearbyServiceAdvertiserDelegateProtocol? = null
    private var browser: MCNearbyServiceBrowser? = null
    private var browserDelegate: MCNearbyServiceBrowserDelegateProtocol? = null

    /**
     * Torn-aware single-active guard. A weave while a live session occupies the
     * slot throws; a self-dropped (Torn) session frees the slot on the next weave
     * with no side-channel — [MCSessionLink] latches Torn on both the drop and
     * close paths, and the slot reads that terminal state directly.
     */
    private val slot = ActiveSeamSlot("MultipeerPeerLinkFactory already has an active session")

    public actual override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New -> openSession()
            is Rendezvous.Existing -> {
                val advertisement = rendezvous.tag
                require(advertisement is MultipeerAdvertisement) {
                    "MultipeerPeerLinkFactory.weave requires a MultipeerAdvertisement, got ${advertisement::class}"
                }
                joinSession(advertisement)
            }
        }

    private fun openSession(): Seam =
        slot.occupy {
            // A prior host session may have torn (self-drop) without an explicit close;
            // its advertiser is still running. Stop it before starting a fresh one —
            // the drop path no longer eagerly stops it (the slot self-heals on this
            // claim instead), so a reuse-after-drop must not leak the stale advertiser.
            stopAdvertising()
            val session =
                MCSession(
                    peer = localPeerId,
                    securityIdentity = null,
                    encryptionPreference = MCEncryptionRequired,
                )
            log.info { "mc.session.create localPeer=$displayName serviceType=$serviceType path=host" }
            val link = MCSessionLink(localPeerId, session)
            session.delegate = link.delegate

            val acceptAll = AcceptAllAdvertiserDelegate(link)
            val advertiser =
                MCNearbyServiceAdvertiser(
                    peer = localPeerId,
                    discoveryInfo = null,
                    serviceType = serviceType,
                ).also { it.delegate = acceptAll }
            advertiser.startAdvertisingPeer()
            log.info { "mc.advertise.start localPeer=$displayName serviceType=$serviceType" }

            this.advertiser = advertiser
            this.advertiserDelegate = acceptAll
            link
        }

    private fun joinSession(advertisement: MultipeerAdvertisement): Seam =
        slot.occupy {
            val activeBrowser =
                browser
                    ?: error(
                        "MultipeerPeerLinkFactory.join requires an active browsing session. " +
                            "Start MultipeerServiceBrowser.discoveries() and keep the flow " +
                            "collected across the join — invitePeer must be sent on the same " +
                            "MCNearbyServiceBrowser instance that discovered the peer.",
                    )
            val target =
                knownPeers[advertisement.handle]
                    ?: error(
                        "Unknown peer handle ${advertisement.handle}. " +
                            "Has the corresponding MultipeerServiceBrowser been started?",
                    )

            val session =
                MCSession(
                    peer = localPeerId,
                    securityIdentity = null,
                    encryptionPreference = MCEncryptionRequired,
                )
            log.info { "mc.session.create localPeer=$displayName serviceType=$serviceType path=join" }
            val link = MCSessionLink(localPeerId, session)
            session.delegate = link.delegate

            log.info {
                "mc.invite.send localPeer=$displayName targetPeer=${target.displayName} timeout=${INVITE_TIMEOUT_SECONDS}s"
            }
            activeBrowser.invitePeer(
                peerID = target,
                toSession = session,
                withContext = null,
                timeout = INVITE_TIMEOUT_SECONDS,
            )
            link
        }

    /**
     * Starts the shared `MCNearbyServiceBrowser` and routes `foundPeer` /
     * `lostPeer` callbacks to [onAdvertisement] (after updating
     * [knownPeers]). Called from `MultipeerServiceBrowser.discoveries` when
     * the flow is collected. Throws if browsing is already active — the
     * `MultipeerServiceBrowser` contract is single-collector.
     */
    internal fun startBrowsing(onAdvertisement: (MultipeerAdvertisement) -> Unit) {
        check(browser == null) { "MultipeerPeerLinkFactory is already browsing" }
        val delegate = BrowserDelegate(this, onAdvertisement)
        val nextBrowser =
            MCNearbyServiceBrowser(
                peer = localPeerId,
                serviceType = serviceType,
            ).also { it.delegate = delegate }
        nextBrowser.startBrowsingForPeers()
        log.info { "mc.browse.start localPeer=$displayName serviceType=$serviceType" }
        browser = nextBrowser
        browserDelegate = delegate
    }

    /**
     * Stops the shared browser and clears the discovered-peer cache.
     * Idempotent — safe to call after [close]. Any in-flight [join] that
     * relied on the browser must already have queued its `invitePeer` call
     * before this fires; the discovery flow stays collected across the join
     * for exactly this reason.
     */
    internal fun stopBrowsing() {
        browser?.let {
            log.info { "mc.browse.stop localPeer=$displayName serviceType=$serviceType" }
            it.stopBrowsingForPeers()
            it.delegate = null
        }
        browser = null
        browserDelegate = null
        knownPeers.clear()
        _visiblePeers.value = emptySet()
    }

    /**
     * Tears down advertising and disconnects the active session, if any.
     * Idempotent.
     */
    public actual fun close() {
        releaseActiveSession()
        stopBrowsing()
    }

    /**
     * Stops host advertising and disconnects the active session. Shared by [close]
     * and [openSession] (stale-advertiser reap). Idempotent. [grabAndRelease][ActiveSeamSlot.grabAndRelease]
     * nulls the slot **before** `disconnect()` so any re-entrant terminal callback
     * from the disconnect finds an empty slot. Leaves browsing untouched — that is
     * owned by the discoveries flow, not a session.
     */
    private fun releaseActiveSession() {
        stopAdvertising()
        (slot.grabAndRelease() as MCSessionLink?)?.session?.disconnect()
    }

    /**
     * Stops the host advertiser and clears its delegate, if any. Idempotent.
     * Split out of [releaseActiveSession] so [openSession] can reap a stale
     * advertiser left running by a prior torn session before starting a new one.
     */
    private fun stopAdvertising() {
        advertiser?.let {
            log.info { "mc.advertise.stop localPeer=$displayName serviceType=$serviceType" }
            it.stopAdvertisingPeer()
        }
        advertiser = null
        advertiserDelegate = null
    }

    private companion object {
        private const val INVITE_TIMEOUT_SECONDS: Double = 30.0
        private const val LOST_PEER_BUFFER: Int = 16
    }

    internal class AcceptAllAdvertiserDelegate(
        private val link: MCSessionLink,
    ) : NSObject(),
        MCNearbyServiceAdvertiserDelegateProtocol {
        override fun advertiser(
            advertiser: MCNearbyServiceAdvertiser,
            didReceiveInvitationFromPeer: MCPeerID,
            withContext: NSData?,
            invitationHandler: (Boolean, MCSession?) -> Unit,
        ) {
            log.info { "mc.invite fromPeer=${didReceiveInvitationFromPeer.displayName} decision=accepted" }
            invitationHandler(true, link.session)
        }
    }

    private class BrowserDelegate(
        private val factory: MultipeerPeerLinkFactory,
        private val onAdvertisement: (MultipeerAdvertisement) -> Unit,
    ) : NSObject(),
        MCNearbyServiceBrowserDelegateProtocol {
        override fun browser(
            browser: MCNearbyServiceBrowser,
            foundPeer: MCPeerID,
            withDiscoveryInfo: Map<Any?, *>?,
        ) {
            val handle = foundPeer.displayName
            log.info { "mc.discover.peer handle=$handle localPeer=${factory.displayName}" }
            factory.knownPeers[handle] = foundPeer
            val ad =
                MultipeerAdvertisement(
                    handle = handle,
                    sessionName = handle,
                    serviceType = factory.serviceType,
                )
            factory._visiblePeers.update { current -> current.filterNot { it.handle == handle }.toSet() + ad }
            onAdvertisement(ad)
        }

        override fun browser(
            browser: MCNearbyServiceBrowser,
            lostPeer: MCPeerID,
        ) {
            val handle = lostPeer.displayName
            log.info { "mc.lose.peer handle=$handle localPeer=${factory.displayName}" }
            factory.knownPeers.remove(handle)
            factory._visiblePeers.update { current -> current.filterNot { it.handle == handle }.toSet() }
            factory.lostPeerHandles.tryEmit(handle)
        }

        override fun browser(
            browser: MCNearbyServiceBrowser,
            didNotStartBrowsingForPeers: NSError,
        ) = Unit
    }
}
